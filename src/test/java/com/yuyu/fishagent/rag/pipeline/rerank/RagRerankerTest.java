package com.yuyu.fishagent.rag.pipeline.rerank;

import com.yuyu.fishagent.rag.config.RagProperties;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;
import com.yuyu.fishagent.common.resilience.CircuitBreakerHelper;
import com.yuyu.fishagent.common.resilience.ResilienceConstants;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagRerankerTest {

    private static RagRecall.RecallHit hit(String id) {
        return new RagRecall.RecallHit(id, "content-" + id, 1.0, RagRecall.RecallSource.TEXT);
    }

    private static List<RagRecall.RecallHit> candidates() {
        return List.of(hit("A"), hit("B"), hit("C"), hit("D"));
    }

    @Test
    void reorderByResultsAppliesApiOrderAndScoreAndTopN() {
        List<Map<String, Object>> results = List.of(
                Map.of("index", 2, "relevance_score", 0.95),
                Map.of("index", 0, "relevance_score", 0.80),
                Map.of("index", 1, "relevance_score", 0.40));

        List<RagRecall.RecallHit> out = DashScopeRagReranker.reorderByResults(candidates(), results, 2);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).id()).isEqualTo("C");
        assertThat(out.get(0).score()).isEqualTo(0.95);
        assertThat(out.get(1).id()).isEqualTo("A");
        assertThat(out.get(1).score()).isEqualTo(0.80);
    }

    @Test
    void reorderByResultsIgnoresOutOfRangeIndex() {
        List<Map<String, Object>> results = List.of(
                Map.of("index", 9, "relevance_score", 0.99),
                Map.of("index", 1, "relevance_score", 0.50));

        List<RagRecall.RecallHit> out = DashScopeRagReranker.reorderByResults(candidates(), results, 3);

        assertThat(out).singleElement().satisfies(hit -> {
            assertThat(hit.id()).isEqualTo("B");
            assertThat(hit.score()).isEqualTo(0.50);
        });
    }

    @Test
    void rerankReturnsTruncatedCandidatesWhenDisabled() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(false);
        properties.getRerank().setApiKey("unused");

        RagReranker reranker = new DashScopeRagReranker(properties, RestClient.builder().baseUrl("http://127.0.0.1").build());

        assertThat(reranker.rerank("query", candidates(), 2))
                .extracting(RagRecall.RecallHit::id)
                .containsExactly("A", "B");
    }

    @Test
    void rerankReturnsTruncatedCandidatesWhenApiKeyBlank() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(true);
        properties.getRerank().setApiKey(" ");

        RagReranker reranker = new DashScopeRagReranker(properties, RestClient.builder().baseUrl("http://127.0.0.1").build());

        assertThat(reranker.rerank("query", candidates(), 3))
                .extracting(RagRecall.RecallHit::id)
                .containsExactly("A", "B", "C");
    }

    @Test
    void rerankFallsBackWhenHttpFailsAndFallbackEnabled() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(true);
        properties.getRerank().setApiKey("test-key");
        properties.getRerank().setFallbackOnError(true);

        RagReranker reranker = new DashScopeRagReranker(properties, RestClient.builder().baseUrl("http://127.0.0.1:1").build());

        assertThat(reranker.rerank("query", candidates(), 2))
                .extracting(RagRecall.RecallHit::id)
                .containsExactly("A", "B");
    }

    @Test
    void rerankFallsBackImmediatelyWhenCircuitBreakerIsOpen() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(true);
        properties.getRerank().setApiKey("test-key");
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker(ResilienceConstants.CB_RERANK).transitionToOpenState();
        CircuitBreakerHelper helper = new CircuitBreakerHelper(registry);

        RagReranker reranker = new DashScopeRagReranker(
                properties,
                RestClient.builder().baseUrl("http://192.0.2.1").build(),
                helper);

        assertThat(reranker.rerank("query", candidates(), 2))
                .extracting(RagRecall.RecallHit::id)
                .containsExactly("A", "B");
    }

    @Test
    void rerankThrowsWhenHttpFailsAndFallbackDisabled() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(true);
        properties.getRerank().setApiKey("test-key");
        properties.getRerank().setFallbackOnError(false);

        RagReranker reranker = new DashScopeRagReranker(properties, RestClient.builder().baseUrl("http://127.0.0.1:1").build());

        assertThatThrownBy(() -> reranker.rerank("query", candidates(), 2))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void extractResultsParsesQwen3RerankTopLevelResults() {
        // qwen3-rerank 响应：results 在顶层，无 output 包裹（与 gte-rerank-v2 的 output.results 不同）
        Map<String, Object> response = Map.of(
                "object", "list",
                "results", List.of(
                        Map.of("index", 2, "relevance_score", 0.95),
                        Map.of("index", 0, "relevance_score", 0.80)),
                "model", "qwen3-rerank");

        List<Map<String, Object>> results = DashScopeRagReranker.extractResults(response);

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).containsEntry("relevance_score", 0.95);
    }

    @Test
    void buildRerankBodyUsesFlatQwen3RerankFormat() {
        // qwen3-rerank：query/documents/top_n 与 model 同层，无 input/parameters 包裹（gte-rerank-v2 才用 input/parameters）
        Map<String, Object> body = DashScopeRagReranker.buildRerankBody(
                "qwen3-rerank", "什么是文本排序", List.of("doc1", "doc2"), 5);

        assertThat(body).containsKeys("model", "query", "documents", "top_n");
        assertThat(body).doesNotContainKeys("input", "parameters");
        assertThat(body.get("documents")).isEqualTo(List.of("doc1", "doc2"));
        assertThat(body.get("top_n")).isEqualTo(5);
    }

    @Test
    void documentsForRerankCapsAtApiLimit() {
        // candidates 已按融合分降序；超过 qwen3-rerank 500 文档上限时只取最高分的 500 条
        List<RagRecall.RecallHit> many = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            many.add(new RagRecall.RecallHit("id" + i, "content" + i, 1.0 / (i + 1), RagRecall.RecallSource.TEXT));
        }

        List<String> docs = DashScopeRagReranker.documentsForRerank(many, DashScopeRagReranker.MAX_RERANK_DOCUMENTS);

        assertThat(docs).hasSize(500);
        assertThat(docs.get(0)).isEqualTo("content0");
    }
}
