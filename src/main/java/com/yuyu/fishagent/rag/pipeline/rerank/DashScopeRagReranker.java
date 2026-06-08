package com.yuyu.fishagent.rag.pipeline.rerank;

import com.yuyu.fishagent.common.resilience.CircuitBreakerHelper;
import com.yuyu.fishagent.common.resilience.ResilienceConstants;
import com.yuyu.fishagent.rag.config.RagProperties;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DashScope {@code qwen3-rerank} 精排实现（原 gte-rerank 已于 2026-05-30 下线）。
 * <p>关闭、无 Key、网络失败或服务返回空结果时，默认降级为候选池前 Top-N，保证对话主链路不断。</p>
 */
@Slf4j
public class DashScopeRagReranker implements RagReranker {

    private static final String RERANK_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";

    private final RagProperties ragProperties;
    private final RestClient restClient;
    private final CircuitBreakerHelper circuitBreakerHelper;

    public DashScopeRagReranker(RagProperties ragProperties, CircuitBreakerHelper circuitBreakerHelper) {
        this(ragProperties, buildClient(ragProperties.getRerank()), circuitBreakerHelper);
    }

    DashScopeRagReranker(RagProperties ragProperties, RestClient restClient) {
        this(ragProperties, restClient, null);
    }

    DashScopeRagReranker(RagProperties ragProperties, RestClient restClient, CircuitBreakerHelper circuitBreakerHelper) {
        this.ragProperties = ragProperties;
        this.restClient = restClient;
        this.circuitBreakerHelper = circuitBreakerHelper;
    }

    @Override
    public List<RagRecall.RecallHit> rerank(String query, List<RagRecall.RecallHit> candidates, int topN) {
        int limit = Math.max(1, topN);
        List<RagRecall.RecallHit> fallback = truncate(candidates, limit);
        RagProperties.Rerank cfg = ragProperties.getRerank();

        if (fallback.isEmpty() || query == null || query.isBlank()) {
            return fallback;
        }
        if (!cfg.isEnabled() || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            log.debug("[RagReranker] 精排未启用或无 apiKey，跳过精排 candidates={}", candidates.size());
            return fallback;
        }

        if (log.isDebugEnabled()) {
            String queryPreview = query.length() > 60 ? query.substring(0, 60) + "…" : query;
            log.debug("[RagReranker] 开始精排 candidates={}, topN={}, model={}, query=[{}]",
                    candidates.size(), limit, cfg.getModel(), queryPreview);
        }

        try {
            Map<String, Object> response = executeRerankRequest(query, candidates, limit, fallback);

            List<Map<String, Object>> results = extractResults(response);
            if (results.isEmpty()) {
                log.warn("[RagReranker] DashScope Rerank 返回空结果，降级到融合候选池 candidates={}", candidates.size());
                return fallback;
            }

            List<RagRecall.RecallHit> reranked = reorderByResults(candidates, results, limit);
            if (log.isDebugEnabled() && !reranked.isEmpty()) {
                log.debug("[RagReranker] 精排完成 input={}, output={}, topScore={}, lowestScore={}",
                        candidates.size(), reranked.size(),
                        String.format("%.4f", reranked.get(0).score()),
                        String.format("%.4f", reranked.get(reranked.size() - 1).score()));
                for (int i = 0; i < Math.min(5, reranked.size()); i++) {
                    RagRecall.RecallHit h = reranked.get(i);
                    log.debug("[RagReranker]   #{} id={}, relevanceScore={}",
                            i + 1, h.id(), String.format("%.4f", h.score()));
                }
            }
            return reranked.isEmpty() ? fallback : reranked;
        } catch (RuntimeException e) {
            if (cfg.isFallbackOnError()) {
                log.warn("[RagReranker] DashScope Rerank 调用失败，降级到融合候选池: {}", e.getMessage());
                return fallback;
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeRerankRequest(String query,
                                                     List<RagRecall.RecallHit> candidates,
                                                     int limit,
                                                     List<RagRecall.RecallHit> fallback) {
        List<String> documents = candidates.stream()
                .map(RagRecall.RecallHit::content)
                .toList();
        java.util.function.Supplier<Map<String, Object>> action = () -> restClient.post()
                .uri(RERANK_PATH)
                .body(Map.of(
                        "model", ragProperties.getRerank().getModel(),
                        "input", Map.of(
                                "query", query,
                                "documents", documents),
                        "parameters", Map.of(
                                "top_n", limit,
                                "return_documents", false)))
                .retrieve()
                .body(Map.class);

        if (circuitBreakerHelper == null) {
            return action.get();
        }
        // 熔断打开时返回一个空响应，后续 extractResults 为空会自然降级为 fallback。
        return circuitBreakerHelper.executeWithCircuitBreaker(
                ResilienceConstants.CB_RERANK,
                action,
                Map.of("output", Map.of("results", List.of())));
    }

    private static RestClient buildClient(RagProperties.Rerank cfg) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, cfg.getTimeoutSeconds()));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + cfg.getApiKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractResults(Map<String, Object> response) {
        if (response == null) {
            return List.of();
        }
        Object output = response.get("output");
        if (!(output instanceof Map<?, ?> outputMap)) {
            return List.of();
        }
        Object results = ((Map<String, Object>) outputMap).get("results");
        if (results instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    /**
     * 将 DashScope 返回的 index 顺序映射回候选池，并把 relevance_score 写回 score。
     * <p>该方法不发 HTTP，作为响应解析的稳定测试入口。</p>
     */
    static List<RagRecall.RecallHit> reorderByResults(List<RagRecall.RecallHit> candidates,
                                                      List<Map<String, Object>> results,
                                                      int topN) {
        if (candidates == null || candidates.isEmpty() || results == null || results.isEmpty()) {
            return List.of();
        }
        List<RagRecall.RecallHit> out = new ArrayList<>();
        int limit = Math.max(1, topN);
        for (Map<String, Object> result : results) {
            if (out.size() >= limit) {
                break;
            }
            Object indexObject = result.get("index");
            if (!(indexObject instanceof Number indexNumber)) {
                continue;
            }
            int index = indexNumber.intValue();
            if (index < 0 || index >= candidates.size()) {
                continue;
            }
            RagRecall.RecallHit base = candidates.get(index);
            double score = result.get("relevance_score") instanceof Number scoreNumber
                    ? scoreNumber.doubleValue()
                    : base.score();
            out.add(new RagRecall.RecallHit(base.id(), base.content(), score, base.source()));
        }
        return out;
    }

    private static List<RagRecall.RecallHit> truncate(List<RagRecall.RecallHit> candidates, int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .limit(Math.max(1, limit))
                .toList();
    }
}
