package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.rag.pipeline.expand.RagQueryExpand;
import com.yuyu.fishagent.rag.pipeline.query.RagQueryRewrite;
import com.yuyu.fishagent.auth.context.UserContext;
import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.rag.config.RagProperties;
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

    /** 单条 ES 命中。 */
    public record RecallHit(String id, String content, double score, RecallSource source) {
    }

    /** 供 Chat 层注入：把本轮用户输入转为可插入系统消息的 RAG 文本（若有命中）。 */
    public interface Augmentation {
        Optional<String> buildAugmentation(String sessionId, String rawUserInput);
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

    public static List<RecallHit> mergeFlatByMaxScore(List<RecallHit> flat, int maxFacts) {
        Map<String, RecallHit> best = new LinkedHashMap<>();
        for (RecallHit h : flat) {
            if (h == null || h.content() == null || h.content().isBlank()) {
                continue;
            }
            String key = h.id() != null && !h.id().isBlank()
                    ? h.id()
                    : "hash:" + Integer.toHexString(Objects.hash(h.content()));
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
    public static final class DefaultAugmentation implements Augmentation {

        private final RagProperties ragProperties;
        private final RagQueryRewrite.QueryRewriter queryRewriter;
        private final RagQueryExpand.SubQueryExpander subQueryExpander;
        private final DocumentSearcher userMemorySearcher;
        private final DocumentSearcher userKnowledgeSearcher;
        private final DocumentSearcher publicKnowledgeSearcher;
        private final ObjectProvider<ElasticsearchOperations> operationsProvider;
        private final ExecutorService recallExecutor;

        public DefaultAugmentation(
                RagProperties ragProperties,
                RagQueryRewrite.QueryRewriter queryRewriter,
                RagQueryExpand.SubQueryExpander subQueryExpander,
                DocumentSearcher userMemorySearcher,
                DocumentSearcher userKnowledgeSearcher,
                DocumentSearcher publicKnowledgeSearcher,
                ObjectProvider<ElasticsearchOperations> operationsProvider,
                @Qualifier("ragRecallExecutor") ExecutorService recallExecutor) {
            this.ragProperties = ragProperties;
            this.queryRewriter = queryRewriter;
            this.subQueryExpander = subQueryExpander;
            this.userMemorySearcher = userMemorySearcher;
            this.userKnowledgeSearcher = userKnowledgeSearcher;
            this.publicKnowledgeSearcher = publicKnowledgeSearcher;
            this.operationsProvider = operationsProvider;
            this.recallExecutor = recallExecutor;
        }

        @Override
        public Optional<String> buildAugmentation(String sessionId, String rawUserInput) {
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
            List<String> subQueries = subQueryExpander.expand(textForExpandAndVector);
            if (subQueries.isEmpty()) {
                return Optional.empty();
            }
            log.debug("[RagRecall] 子查询扩展 sid={}, subCount={}, subQueries={}", sessionId, subQueries.size(), subQueries);

            // 虚拟线程执行召回时不会继承 Servlet ThreadLocal；私有索引依赖 UserContextHolder.userId，必须在异步任务内回放快照。
            final UserContext ragUserSnapshot = UserContextHolder.get();

            int perK = Math.max(1, ragProperties.getRecall().getPerSubquerySize());
            // 每个子查询：用户对话记忆索引、用户文档知识索引、公有知识索引各跑一次文本召回（虚拟线程池并发）
            List<CompletableFuture<List<RecallHit>>> textFutures = new ArrayList<>();
            for (String sq : subQueries) {
                textFutures.add(CompletableFuture.supplyAsync(
                        () -> runWithRagUserContext(ragUserSnapshot,
                                () -> safeTextSearch(userMemorySearcher, sessionId, sq, perK)),
                        recallExecutor));
                textFutures.add(CompletableFuture.supplyAsync(
                        () -> runWithRagUserContext(ragUserSnapshot,
                                () -> safeTextSearch(userKnowledgeSearcher, sessionId, sq, perK)),
                        recallExecutor));
                textFutures.add(CompletableFuture.supplyAsync(
                        () -> safeTextSearch(publicKnowledgeSearcher, sessionId, sq, perK),
                        recallExecutor));
            }

            CompletableFuture<List<RecallHit>> userVecFuture = CompletableFuture.supplyAsync(
                    () -> runWithRagUserContext(ragUserSnapshot,
                            () -> safeVectorSearch(userMemorySearcher, sessionId, textForExpandAndVector, perK)),
                    recallExecutor);
            CompletableFuture<List<RecallHit>> userKnowledgeVecFuture = CompletableFuture.supplyAsync(
                    () -> runWithRagUserContext(ragUserSnapshot,
                            () -> safeVectorSearch(userKnowledgeSearcher, sessionId, textForExpandAndVector, perK)),
                    recallExecutor);
            CompletableFuture<List<RecallHit>> publicVecFuture = CompletableFuture.supplyAsync(
                    () -> safeVectorSearch(publicKnowledgeSearcher, sessionId, textForExpandAndVector, perK),
                    recallExecutor);

            CompletableFuture.allOf(
                    userVecFuture,
                    userKnowledgeVecFuture,
                    publicVecFuture,
                    CompletableFuture.allOf(textFutures.toArray(CompletableFuture[]::new))
            ).join();

            List<List<RecallHit>> batches = new ArrayList<>();
            for (CompletableFuture<List<RecallHit>> f : textFutures) {
                batches.add(f.join());
            }
            batches.add(userVecFuture.join());
            batches.add(userKnowledgeVecFuture.join());
            batches.add(publicVecFuture.join());

            // ── 召回明细 DEBUG 日志 ──
            if (log.isDebugEnabled()) {
                int memTextSum = 0, memVec = userVecFuture.join().size();
                int ukTextSum = 0, ukVec = userKnowledgeVecFuture.join().size();
                int pubTextSum = 0, pubVec = publicVecFuture.join().size();
                for (int i = 0; i < subQueries.size(); i++) {
                    // per sub-query: [mem, uk, pub] × 3 text searchers
                    int base = i * 3;
                    memTextSum += batches.get(base).size();
                    ukTextSum += batches.get(base + 1).size();
                    pubTextSum += batches.get(base + 2).size();
                }
                log.debug("[RagRecall] 召回明细 sid={} subCount={}: "
                                + "记忆=[text:{} vec:{}] 用户知识=[text:{} vec:{}] 公共知识=[text:{} vec:{}]",
                        sessionId, subQueries.size(),
                        memTextSum, memVec, ukTextSum, ukVec, pubTextSum, pubVec);
            }

            int maxFacts = Math.max(1, ragProperties.getRender().getMaxInjectedFacts());
            List<RecallHit> merged = mergeByMaxScore(batches, maxFacts);
            if (merged.isEmpty()) {
                log.debug("[RagRecall] 无召回命中 sid={}", sessionId);
                return Optional.empty();
            }

            String block = renderBlock(merged);
            log.debug("[RagRecall] 注入 sid={}, hits={}, blockLen={}", sessionId, merged.size(), block.length());
            return Optional.of(block);
        }

        private List<RecallHit> safeTextSearch(DocumentSearcher searcher, String sessionId, String sq, int perK) {
            try {
                return searcher.searchByText(sessionId, sq, perK);
            } catch (Exception e) {
                log.warn("[RagRecall] 子查询文本召回失败 sqLen={}: {}", sq.length(), e.getMessage());
                return List.of();
            }
        }

        private List<RecallHit> safeVectorSearch(DocumentSearcher searcher, String sessionId, String rewritten, int perK) {
            try {
                return searcher.searchByVector(sessionId, rewritten, perK);
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
            // 避免模型对用户复述「长期记忆/片段/检索」等元话术；事实条仍编号便于模型对照。
            String header = """
                    【内部参考】以下为可能与当前对话相关的已知事实（仅使用其中已列内容，勿编造）。
                    回复用户时请自然承接，勿提及「记忆」「片段」「检索」「上下文」「系统提示」「根据上面/本段」或交代信息来源。
                    可确认的事实：
                    """;
            int maxChars = Math.max(200, ragProperties.getRender().getMaxInjectedChars());
            StringBuilder sb = new StringBuilder(header);
            int n = 1;
            for (RecallHit h : merged) {
                String line = n + ". " + h.content().replace("\r\n", "\n").replace("\n", " ") + "\n";
                if (sb.length() + line.length() > maxChars) {
                    break;
                }
                sb.append(line);
                n++;
            }
            return sb.toString().trim();
        }
    }
}
