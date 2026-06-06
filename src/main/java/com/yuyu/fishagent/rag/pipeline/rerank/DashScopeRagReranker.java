package com.yuyu.fishagent.rag.pipeline.rerank;

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

    public DashScopeRagReranker(RagProperties ragProperties) {
        this(ragProperties, buildClient(ragProperties.getRerank()));
    }

    DashScopeRagReranker(RagProperties ragProperties, RestClient restClient) {
        this.ragProperties = ragProperties;
        this.restClient = restClient;
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
            return fallback;
        }

        try {
            List<String> documents = candidates.stream()
                    .map(RagRecall.RecallHit::content)
                    .toList();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(RERANK_PATH)
                    .body(Map.of(
                            "model", cfg.getModel(),
                            "input", Map.of(
                                    "query", query,
                                    "documents", documents),
                            "parameters", Map.of(
                                    "top_n", limit,
                                    "return_documents", false)))
                    .retrieve()
                    .body(Map.class);

            List<Map<String, Object>> results = extractResults(response);
            if (results.isEmpty()) {
                log.warn("[RagReranker] DashScope Rerank 返回空结果，降级到融合候选池 candidates={}", candidates.size());
                return fallback;
            }

            List<RagRecall.RecallHit> reranked = reorderByResults(candidates, results, limit);
            return reranked.isEmpty() ? fallback : reranked;
        } catch (RuntimeException e) {
            if (cfg.isFallbackOnError()) {
                log.warn("[RagReranker] DashScope Rerank 调用失败，降级到融合候选池: {}", e.getMessage());
                return fallback;
            }
            throw e;
        }
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
