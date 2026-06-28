package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.rag.pipeline.expand.RagQueryExpand;
import com.yuyu.fishagent.rag.pipeline.expand.RagHydeService;
import com.yuyu.fishagent.common.metrics.ChatMetrics;
import com.yuyu.fishagent.common.util.TokenEstimator;
import com.yuyu.fishagent.rag.pipeline.fusion.RagScoreFusion;
import com.yuyu.fishagent.rag.pipeline.query.RagQueryRewrite;
import com.yuyu.fishagent.auth.context.UserContext;
import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.card.mapper.CardRelationMapper;
import com.yuyu.fishagent.card.mapper.KnowledgeCardMapper;
import com.yuyu.fishagent.common.resilience.CircuitBreakerHelper;
import com.yuyu.fishagent.common.resilience.ResilienceConstants;
import com.yuyu.fishagent.common.trace.MdcAsync;
import com.yuyu.fishagent.rag.config.KnowledgeProperties;
import com.yuyu.fishagent.rag.config.RagProperties;
import com.yuyu.fishagent.rag.pipeline.rerank.RagReranker;
import com.yuyu.fishagent.rag.tracing.RagQualityLogger;
import com.yuyu.fishagent.rag.tracing.RagTraceDocument;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * 长期记忆召回与编排（第三类）：DTO、ES 访问、合并、对 {@link com.yuyu.fishagent.chat.ChatService} 暴露的片段生成。
 * <p>与 {@link com.yuyu.fishagent.rag.pipeline.query.RagQueryRewrite}、{@link RagQueryExpand} 通过接口协作，不包含上述两类的 Bean 定义。</p>
 */
@Slf4j
public final class RagRecall {

    private RagRecall() {
    }

    /** 命中来源。 */
    public enum RecallSource {
        TEXT,
        VECTOR
    }

    /**
     * 单条召回命中。
     *
     * <p>除内容与分数外，携带来源、权威度、时间与文档定位信息，供后续 provenance 加权、邻块扩展和渲染标签复用。
     * 四参数构造保留旧调用方兼容；新字段缺失时各后处理组件会按来源给出保守默认值。</p>
     */
    public record RecallHit(String id, String content, double score, RecallSource source,
                            String sourceLabel, Double authority, Long createdAt,
                            String docId, Integer chunkIndex, String docName) {

        /** 旧 9 参构造（无 docName），向后兼容：docName=null。 */
        public RecallHit(String id, String content, double score, RecallSource source,
                         String sourceLabel, Double authority, Long createdAt,
                         String docId, Integer chunkIndex) {
            this(id, content, score, source, sourceLabel, authority, createdAt, docId, chunkIndex, null);
        }

        public RecallHit(String id, String content, double score, RecallSource source) {
            this(id, content, score, source, null, null, null, null, null, null);
        }

        public RecallHit withScore(double newScore) {
            return new RecallHit(id, content, newScore, source, sourceLabel, authority, createdAt, docId, chunkIndex, docName);
        }

        public RecallHit withContent(String newContent) {
            return new RecallHit(id, newContent, score, source, sourceLabel, authority, createdAt, docId, chunkIndex, docName);
        }

        public String effectiveSourceLabel() {
            return sourceLabel == null || sourceLabel.isBlank() ? "公开" : sourceLabel.trim();
        }
    }

    /**
     * 带出处命中的增强结果 [v6.4 Top1]：{@code block} 注入模型上下文，{@code hits} 下发前端做来源溯源。
     *
     * <p>hits 为精排 + provenance + expand 后实际注入的 finalHits；无命中（candidates/finalHits 空）时
     * 整个 Optional 为 empty，既无 block 也无 hits。</p>
     */
    public record AugmentationResult(String block, List<RecallHit> hits) {
    }

    /** 供 Chat 层注入：把本轮用户输入转为可插入系统消息的 RAG 文本（若有命中）。 */
    public interface Augmentation {
        Optional<String> buildAugmentation(String sessionId, String rawUserInput);

        /**
         * 带对话上下文的增强。默认忽略上下文，委托给 2 参数版本。
         */
        default Optional<String> buildAugmentation(String sessionId, String rawUserInput, String contextHint) {
            return buildAugmentation(sessionId, rawUserInput);
        }

        /**
         * 带 token 预算的增强。默认忽略预算，委托给上下文版本，保持现有实现兼容。
         */
        default Optional<String> buildAugmentation(String sessionId, String rawUserInput,
                                                   String contextHint, int tokenBudget) {
            return buildAugmentation(sessionId, rawUserInput, contextHint);
        }

        /**
         * 带出处命中的增强 [v6.4 Top1]：返回 block + 召回命中，供前端来源溯源。
         * 默认忽略命中、委托给 4 参数版本（返回空 hits），保持现有实现兼容。
         */
        default Optional<AugmentationResult> buildAugmentationWithSources(String sessionId, String rawUserInput,
                                                                          String contextHint, int tokenBudget) {
            return buildAugmentation(sessionId, rawUserInput, contextHint, tokenBudget)
                    .map(block -> new AugmentationResult(block, List.of()));
        }
    }

    /**
     * 单一路 ES 索引上的检索抽象（用户私有记忆或公有知识库各自实现）。
     * <p>首参 {@code sessionId} 不参与私有索引过滤：{@link com.yuyu.fishagent.rag.pipeline.recall.UserMemoryElasticsearchSearcher}
     * 按当前登录用户的 {@code user_id}（{@link UserContextHolder}）做 ES term filter；
     * {@code sessionId} 仅为兼容签名 / 未来扩展，切勿误认为「按会话召回」。</p>
     */
    public interface DocumentSearcher {
        List<RecallHit> searchByText(String sessionId, String subQueryText, int size);

        List<RecallHit> searchByVector(String sessionId, String textToEmbed, int size);
    }

    public static List<RecallHit> mergeByMaxScore(List<List<RecallHit>> batches, int maxFacts) {
        List<RecallHit> flat = new ArrayList<>();
        for (List<RecallHit> batch : batches) {
            if (batch != null) {
                flat.addAll(batch);
            }
        }
        return mergeFlatByMaxScore(flat, maxFacts);
    }

    /**
     * 召回命中去重 key：优先使用 ES 文档 id；无 id 时使用内容 hash。
     * <p>旧的 max-score 合并与新的 RRF 融合共用这一规则，避免两条路径对“同一条命中”的判断不一致。</p>
     */
    public static String dedupKey(RecallHit hit) {
        if (hit == null || hit.content() == null || hit.content().isBlank()) {
            return null;
        }
        return hit.id() != null && !hit.id().isBlank()
                ? hit.id()
                : "hash:" + Integer.toHexString(Objects.hash(hit.content()));
    }

    public static List<RecallHit> mergeFlatByMaxScore(List<RecallHit> flat, int maxFacts) {
        Map<String, RecallHit> best = new LinkedHashMap<>();
        for (RecallHit h : flat) {
            String key = dedupKey(h);
            if (key == null) {
                continue;
            }
            best.merge(key, h, (a, b) -> a.score() >= b.score() ? a : b);
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(RecallHit::score).reversed())
                .limit(Math.max(0, maxFacts))
                .toList();
    }

    /**
     * 串联：可选查询重写 → 多查询扩展（始终）→ 虚拟线程并发检索 → 合并 → 渲染。
     */
    public static class DefaultAugmentation implements Augmentation {

        private final RagProperties ragProperties;
        private final RagQueryRewrite.QueryRewriter queryRewriter;
        private final RagQueryExpand.SubQueryExpander subQueryExpander;
        private final DocumentSearcher userMemorySearcher;
        private final DocumentSearcher userKnowledgeSearcher;
        private final DocumentSearcher userKnowledgeCardSearcher;
        private final DocumentSearcher publicKnowledgeSearcher;
        private final ObjectProvider<ElasticsearchOperations> operationsProvider;
        private final ExecutorService recallExecutor;
        private final RagReranker reranker;
        private final RagHydeService hydeService;
        private final RagQualityLogger qualityLogger;
        private final CircuitBreakerHelper circuitBreakerHelper;
        private final ChatMetrics chatMetrics;
        private final ProvenanceBooster provenanceBooster;
        private final ContextExpander contextExpander;
        private final CardGraphExpander cardGraphExpander;

        public DefaultAugmentation(
                RagProperties ragProperties,
                KnowledgeProperties knowledgeProperties,
                RagQueryRewrite.QueryRewriter queryRewriter,
                RagQueryExpand.SubQueryExpander subQueryExpander,
                DocumentSearcher userMemorySearcher,
                DocumentSearcher userKnowledgeSearcher,
                DocumentSearcher userKnowledgeCardSearcher,
                DocumentSearcher publicKnowledgeSearcher,
                ObjectProvider<ElasticsearchOperations> operationsProvider,
                @Qualifier("ragRecallExecutor") ExecutorService recallExecutor,
                RagReranker reranker,
                RagHydeService hydeService,
                RagQualityLogger qualityLogger,
                CircuitBreakerHelper circuitBreakerHelper,
                ChatMetrics chatMetrics,
                CardRelationMapper cardRelationMapper,
                KnowledgeCardMapper knowledgeCardMapper) {
            this.ragProperties = ragProperties;
            this.queryRewriter = queryRewriter;
            this.subQueryExpander = subQueryExpander;
            this.userMemorySearcher = userMemorySearcher;
            this.userKnowledgeSearcher = userKnowledgeSearcher;
            this.userKnowledgeCardSearcher = userKnowledgeCardSearcher;
            this.publicKnowledgeSearcher = publicKnowledgeSearcher;
            this.operationsProvider = operationsProvider;
            this.recallExecutor = recallExecutor;
            this.reranker = reranker;
            this.hydeService = hydeService;
            this.qualityLogger = qualityLogger;
            this.circuitBreakerHelper = circuitBreakerHelper;
            this.chatMetrics = chatMetrics;
            this.provenanceBooster = new ProvenanceBooster(ragProperties);
            this.contextExpander = new ContextExpander(ragProperties, knowledgeProperties, operationsProvider);
            this.cardGraphExpander = new CardGraphExpander(cardRelationMapper, knowledgeCardMapper);
        }

        @Override
        public Optional<String> buildAugmentation(String sessionId, String rawUserInput) {
            return doBuildAugmentation(sessionId, rawUserInput, null, 0).map(AugmentationResult::block);
        }

        @Override
        public Optional<String> buildAugmentation(String sessionId, String rawUserInput, String contextHint) {
            return doBuildAugmentation(sessionId, rawUserInput, contextHint, 0).map(AugmentationResult::block);
        }

        @Override
        @Observed(name = "rag.recall", contextualName = "RAG recall")
        public Optional<String> buildAugmentation(String sessionId, String rawUserInput,
                                                  String contextHint, int tokenBudget) {
            return doBuildAugmentation(sessionId, rawUserInput, contextHint, tokenBudget).map(AugmentationResult::block);
        }

        @Override
        public Optional<AugmentationResult> buildAugmentationWithSources(String sessionId, String rawUserInput,
                                                                          String contextHint, int tokenBudget) {
            return doBuildAugmentation(sessionId, rawUserInput, contextHint, tokenBudget);
        }

        private Optional<AugmentationResult> doBuildAugmentation(String sessionId, String rawUserInput,
                                                                 String contextHint, int tokenBudget) {
            if (!ragProperties.isEnabled()) {
                return Optional.empty();
            }
            if (operationsProvider.getIfAvailable() == null) {
                log.debug("[RagRecall] ElasticsearchOperations 不可用，跳过 RAG");
                return Optional.empty();
            }
            if (rawUserInput == null || rawUserInput.isBlank()) {
                return Optional.empty();
            }
            long totalStart = System.currentTimeMillis();
            Long traceUserId = UserContextHolder.currentUserIdOrNull();

            // 查询重写：仅当 fish.rag.rewrite-enabled=true 时走 QueryRewriter；否则用原文 trim（不做 Identity 规范化，避免隐式改写）
            final String textForExpandAndVector;
            if (ragProperties.isRewriteEnabled()) {
                textForExpandAndVector = queryRewriter.rewrite(rawUserInput, new RagQueryRewrite.RewriteContext(sessionId));
                log.debug("[RagRecall] 查询重写 sid={}, rawLen={}, rewrittenLen={}, rewritten=[{}]",
                        sessionId, rawUserInput.length(), textForExpandAndVector.length(),
                        textForExpandAndVector.length() > 200 ? textForExpandAndVector.substring(0, 200) + "…" : textForExpandAndVector);
            } else {
                textForExpandAndVector = rawUserInput.trim();
            }
            if (textForExpandAndVector.isBlank()) {
                return Optional.empty();
            }

            // 多查询扩展：无单独配置项，RAG 开启时始终执行
            List<String> subQueries = subQueryExpander.expand(textForExpandAndVector, contextHint);
            if (subQueries.isEmpty()) {
                return Optional.empty();
            }
            log.debug("[RagRecall] 子查询扩展 sid={}, subCount={}, subQueries={}", sessionId, subQueries.size(), subQueries);

            // HyDE 默认关闭：开启时仅替换向量腿文本，文本召回仍使用真实查询/子查询。
            String hyp = hydeService.generate(textForExpandAndVector);
            final String vectorText = (hyp != null && !hyp.isBlank()) ? hyp : textForExpandAndVector;

            // 虚拟线程执行召回时不会继承 Servlet ThreadLocal；私有索引依赖 UserContextHolder.userId，必须在异步任务内回放快照。
            final UserContext ragUserSnapshot = UserContextHolder.get();

            long recallStart = System.currentTimeMillis();
            int perK = Math.max(1, ragProperties.getRecall().getPerSubquerySize());
            // 每个子查询：用户对话记忆、用户文档知识、用户知识卡片、公有知识各跑一次文本召回（虚拟线程池并发）。
            List<CompletableFuture<List<RecallHit>>> textFutures = new ArrayList<>();
            for (String sq : subQueries) {
                textFutures.add(MdcAsync.mdcSupplyAsync(
                        () -> runWithRagUserContext(ragUserSnapshot,
                                () -> safeTextSearch(userMemorySearcher, sessionId, sq, perK)),
                        recallExecutor));
                textFutures.add(MdcAsync.mdcSupplyAsync(
                        () -> runWithRagUserContext(ragUserSnapshot,
                                () -> safeTextSearch(userKnowledgeSearcher, sessionId, sq, perK)),
                        recallExecutor));
                textFutures.add(MdcAsync.mdcSupplyAsync(
                        () -> runWithRagUserContext(ragUserSnapshot,
                                () -> safeTextSearch(userKnowledgeCardSearcher, sessionId, sq, perK)),
                        recallExecutor));
                textFutures.add(MdcAsync.mdcSupplyAsync(
                        () -> safeTextSearch(publicKnowledgeSearcher, sessionId, sq, perK),
                        recallExecutor));
            }

            CompletableFuture<List<RecallHit>> userVecFuture = MdcAsync.mdcSupplyAsync(
                    () -> runWithRagUserContext(ragUserSnapshot,
                            () -> safeVectorSearch(userMemorySearcher, sessionId, vectorText, perK)),
                    recallExecutor);
            CompletableFuture<List<RecallHit>> userKnowledgeVecFuture = MdcAsync.mdcSupplyAsync(
                    () -> runWithRagUserContext(ragUserSnapshot,
                            () -> safeVectorSearch(userKnowledgeSearcher, sessionId, vectorText, perK)),
                    recallExecutor);
            CompletableFuture<List<RecallHit>> userKnowledgeCardVecFuture = MdcAsync.mdcSupplyAsync(
                    () -> runWithRagUserContext(ragUserSnapshot,
                            () -> safeVectorSearch(userKnowledgeCardSearcher, sessionId, vectorText, perK)),
                    recallExecutor);
            CompletableFuture<List<RecallHit>> publicVecFuture = MdcAsync.mdcSupplyAsync(
                    () -> safeVectorSearch(publicKnowledgeSearcher, sessionId, vectorText, perK),
                    recallExecutor);

            CompletableFuture.allOf(
                    userVecFuture,
                    userKnowledgeVecFuture,
                    userKnowledgeCardVecFuture,
                    publicVecFuture,
                    CompletableFuture.allOf(textFutures.toArray(CompletableFuture[]::new))
            ).join();

            List<List<RecallHit>> batches = new ArrayList<>();
            for (CompletableFuture<List<RecallHit>> f : textFutures) {
                batches.add(f.join());
            }
            batches.add(userVecFuture.join());
            batches.add(userKnowledgeVecFuture.join());
            batches.add(userKnowledgeCardVecFuture.join());
            batches.add(publicVecFuture.join());

            long recallLatencyMs = System.currentTimeMillis() - recallStart;
            int recallTotalHits = 0;
            for (List<RecallHit> batch : batches) {
                if (batch != null) {
                    recallTotalHits += batch.size();
                }
            }

            // ── 召回明细 DEBUG 日志 ──
            if (log.isDebugEnabled()) {
                int memTextSum = 0, memVec = userVecFuture.join().size();
                int ukTextSum = 0, ukVec = userKnowledgeVecFuture.join().size();
                int cardTextSum = 0, cardVec = userKnowledgeCardVecFuture.join().size();
                int pubTextSum = 0, pubVec = publicVecFuture.join().size();
                for (int i = 0; i < subQueries.size(); i++) {
                    // per sub-query: [mem, uk, card, pub] × 4 text searchers
                    int base = i * 4;
                    memTextSum += batches.get(base).size();
                    ukTextSum += batches.get(base + 1).size();
                    cardTextSum += batches.get(base + 2).size();
                    pubTextSum += batches.get(base + 3).size();
                }
                log.debug("[RagRecall] 召回明细 sid={} subCount={}: "
                                + "记忆=[text:{} vec:{}] 用户知识=[text:{} vec:{}] 知识卡片=[text:{} vec:{}] 公共知识=[text:{} vec:{}]",
                        sessionId, subQueries.size(),
                        memTextSum, memVec, ukTextSum, ukVec, cardTextSum, cardVec, pubTextSum, pubVec);
            }

            int maxFacts = Math.max(1, ragProperties.getRender().getMaxInjectedFacts());
            int poolSize = Math.max(maxFacts, ragProperties.getFusion().getCandidatePoolSize());

            RagTraceDocument trace = new RagTraceDocument();
            trace.setTraceId(UUID.randomUUID().toString());
            trace.setUserId(traceUserId == null ? null : String.valueOf(traceUserId));
            trace.setSessionId(sessionId);
            trace.setOriginalQuery(rawUserInput);
            trace.setRewrittenQuery(ragProperties.isRewriteEnabled() ? textForExpandAndVector : null);
            trace.setExpandedQueries(subQueries);
            trace.setRecallTotalHits(recallTotalHits);
            trace.setRecallLatencyMs(recallLatencyMs);
            trace.setHydeUsed(!vectorText.equals(textForExpandAndVector));
            trace.setCreatedAt(System.currentTimeMillis());

            // 候选池阶段：RRF 统一文本路 / 向量路的排名尺度；关闭时回退旧 max-score 合并。
            List<RecallHit> candidates = chatMetrics.ragLegTimer(ChatMetrics.RagLeg.FUSION).record(() ->
                    ragProperties.getFusion().isEnabled()
                            ? RagScoreFusion.fuseByRrf(batches, ragProperties.getFusion().getRrfK(), poolSize)
                            : mergeByMaxScore(batches, poolSize));
            log.debug("[RagRecall] 融合 sid={}, strategy={}, batchesIn={}, rawHits={}, dedupedCandidates={}",
                    sessionId, ragProperties.getFusion().isEnabled() ? "RRF" : "maxScore",
                    batches.size(), recallTotalHits, candidates.size());
            if (candidates.isEmpty()) {
                log.debug("[RagRecall] 无召回命中 sid={}, rawHits={}, fusionStrategy={}",
                        sessionId, recallTotalHits, ragProperties.getFusion().isEnabled() ? "RRF" : "maxScore");
                trace.setTotalLatencyMs(System.currentTimeMillis() - totalStart);
                qualityLogger.log(trace);
                return Optional.empty();
            }
            trace.setRecallDedupedHits(candidates.size());
            trace.setFusionTopN(candidates.size());
            trace.setRerankInputCount(candidates.size());

            // 精排阶段：Reranker 内部封装关闭 / 无 Key / 异常降级，编排层保持直线流程。
            // rerank / provenance / expand 三段分别计时：rerank_latency_ms 只测精排，
            // provenance/expand 各自独立，避免与 Micrometer leg=rerank 口径不一致。
            int topN = Math.min(maxFacts, Math.max(1, ragProperties.getRerank().getTopN()));
            long rerankStart = System.currentTimeMillis();
            List<RecallHit> reranked = chatMetrics.ragLegTimer(ChatMetrics.RagLeg.RERANK).record(() ->
                    reranker.rerank(textForExpandAndVector, candidates, topN));
            trace.setRerankLatencyMs(System.currentTimeMillis() - rerankStart);

            long provenanceStart = System.currentTimeMillis();
            List<RecallHit> provenance = chatMetrics.ragLegTimer(ChatMetrics.RagLeg.PROVENANCE).record(() ->
                    provenanceBooster.boost(reranked, System.currentTimeMillis()));
            trace.setProvenanceLatencyMs(System.currentTimeMillis() - provenanceStart);

            long expandStart = System.currentTimeMillis();
            List<RecallHit> finalHits = chatMetrics.ragLegTimer(ChatMetrics.RagLeg.EXPAND).record(() ->
                    cardGraphExpander.expand(contextExpander.expand(provenance)));
            trace.setExpandLatencyMs(System.currentTimeMillis() - expandStart);
            if (finalHits.isEmpty()) {
                log.debug("[RagRecall] 精排后无命中 sid={}, rerankInput={}, rerankTopN={}",
                        sessionId, candidates.size(), topN);
                trace.setTotalLatencyMs(System.currentTimeMillis() - totalStart);
                qualityLogger.log(trace);
                return Optional.empty();
            }

            String block = tokenBudget > 0 ? renderBlock(finalHits, tokenBudget) : renderBlock(finalHits);
            trace.setRerankTopScore(finalHits.get(0).score());
            trace.setRerankLowestScore(finalHits.get(finalHits.size() - 1).score());
            trace.setInjectedFactCount(finalHits.size());
            trace.setInjectedFacts(qualityLogger.toInjectedFacts(finalHits));
            trace.setInjectedTotalChars(block.length());
            trace.setTotalLatencyMs(System.currentTimeMillis() - totalStart);
            qualityLogger.log(trace);

            log.debug("[RagRecall] 注入完成 sid={}, candidatePool={}, finalHits={}, blockLen={}, totalLatencyMs={}",
                    sessionId, candidates.size(), finalHits.size(), block.length(),
                    trace.getTotalLatencyMs());
            for (int i = 0; i < finalHits.size(); i++) {
                RecallHit hit = finalHits.get(i);
                String preview = hit.content().length() > 80
                        ? hit.content().substring(0, 80) + "…"
                        : hit.content();
                log.debug("[RagRecall]   #{} id={}, score={}, source={}, preview=[{}]",
                        i + 1, hit.id(), String.format("%.4f", hit.score()), hit.source(), preview);
            }
            return Optional.of(new AugmentationResult(block, finalHits));
        }

        private List<RecallHit> safeTextSearch(DocumentSearcher searcher, String sessionId, String sq, int perK) {
            try {
                // 文本召回统一共享 es-text 熔断器；打开时直接返回空列表，让 RAG 自动降级。
                return chatMetrics.ragLegTimer(ChatMetrics.RagLeg.TEXT).record(() -> circuitBreakerHelper.executeWithCircuitBreaker(
                        ResilienceConstants.CB_ES_TEXT,
                        () -> searcher.searchByText(sessionId, sq, perK),
                        List.of()));
            } catch (Exception e) {
                log.warn("[RagRecall] 子查询文本召回失败 sqLen={}: {}", sq.length(), e.getMessage());
                return List.of();
            }
        }

        private List<RecallHit> safeVectorSearch(DocumentSearcher searcher, String sessionId, String rewritten, int perK) {
            try {
                // 向量召回包含 Embedding + kNN 查询，统一共享 es-vector 熔断器。
                return chatMetrics.ragLegTimer(ChatMetrics.RagLeg.VECTOR).record(() -> circuitBreakerHelper.executeWithCircuitBreaker(
                        ResilienceConstants.CB_ES_VECTOR,
                        () -> searcher.searchByVector(sessionId, rewritten, perK),
                        List.of()));
            } catch (Exception e) {
                log.warn("[RagRecall] 向量召回失败: {}", e.getMessage());
                return List.of();
            }
        }

        /**
         * 在异步召回线程中临时挂载用户上下文，供私有索引按 {@code user_id} 过滤。
         */
        private static <T> T runWithRagUserContext(UserContext ctx, Supplier<T> supplier) {
            if (ctx != null) {
                UserContextHolder.set(ctx);
            }
            try {
                return supplier.get();
            } finally {
                UserContextHolder.clear();
            }
        }

        private String renderBlock(List<RecallHit> merged) {
            int maxChars = Math.max(200, ragProperties.getRender().getMaxInjectedChars());
            return renderBlockByChars(merged, maxChars);
        }

        /**
         * Render RAG facts within a fact-line token budget.
         * <p>
         * The fixed instruction header is not charged to the budget so small
         * budgets can still include at least one concise fact when possible.
         * </p>
         */
        static String renderBlock(List<RecallHit> merged, int tokenBudget) {
            if (tokenBudget <= 0) {
                return renderBlockByChars(merged, 4_000);
            }
            StringBuilder sb = new StringBuilder(ragHeader());
            int usedTokens = 0;
            int n = 1;
            for (RecallHit h : merged) {
                String rawLine = n + ". " + formatFactLine(h);
                String fittedLine = rawLine;
                int remainingTokens = tokenBudget - usedTokens;
                if (TokenEstimator.estimate(rawLine) > remainingTokens) {
                    if (n > 1) {
                        break;
                    }
                    fittedLine = fitLineToTokenBudget(rawLine, remainingTokens);
                }
                String line = fittedLine + "\n";
                int lineTokens = TokenEstimator.estimate(line);
                if (usedTokens + lineTokens > tokenBudget) {
                    break;
                }
                usedTokens += lineTokens;
                sb.append(line);
                n++;
            }
            return sb.toString().trim();
        }

        private static String fitLineToTokenBudget(String line, int remainingTokens) {
            if (remainingTokens <= 0 || TokenEstimator.estimate(line) <= remainingTokens) {
                return line;
            }
            String suffix = "…";
            int low = 0;
            int high = line.length();
            String best = "";
            while (low <= high) {
                int mid = (low + high) >>> 1;
                String candidate = line.substring(0, mid).trim() + suffix;
                if (TokenEstimator.estimate(candidate) <= remainingTokens) {
                    best = candidate;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            return best.isBlank() ? line : best;
        }

        private static String renderBlockByChars(List<RecallHit> merged, int maxChars) {
            StringBuilder sb = new StringBuilder(ragHeader());
            int n = 1;
            for (RecallHit h : merged) {
                String line = n + ". " + formatFactLine(h) + "\n";
                if (sb.length() + line.length() > maxChars) {
                    break;
                }
                sb.append(line);
                n++;
            }
            return sb.toString().trim();
        }

        private static String ragHeader() {
            // 避免模型对用户复述「长期记忆/片段/检索」等元话术；事实条仍编号便于模型对照。
            return """
                    【内部参考】以下为可能与当前对话相关的已知事实（仅使用其中已列内容，勿编造）。
                    若多条参考事实彼此冲突，请优先采用来源更权威、时间更新的事实；无法判断时保持谨慎，不要强行合并。
                    回复用户时请自然承接，勿提及「记忆」「片段」「检索」「上下文」「系统提示」「根据上面/本段」或交代信息来源。
                    可确认的事实：
                    """;
        }

        private static String formatFactLine(RecallHit hit) {
            String content = hit.content() == null ? "" : hit.content().replace("\r\n", "\n").replace("\n", " ").trim();
            String label = hit.effectiveSourceLabel();
            if ("记忆".equals(label)) {
                String age = MemoryAgeLabel.format(hit.createdAt(), java.time.Clock.systemDefaultZone());
                return "[记忆 · " + age + "] " + content;
            }
            if (hit.createdAt() != null && hit.createdAt() > 0) {
                String month = DateTimeFormatter.ofPattern("yyyy-MM")
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(hit.createdAt()));
                return "[来源:" + label + "·" + month + "] " + content;
            }
            return "[来源:" + label + "] " + content;
        }
    }

}
