package com.yuyu.fishagent.rag.pipeline.rerank;

import com.yuyu.fishagent.rag.config.RagProperties;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

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
    void rerankThrowsWhenHttpFailsAndFallbackDisabled() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(true);
        properties.getRerank().setApiKey("test-key");
        properties.getRerank().setFallbackOnError(false);

        RagReranker reranker = new DashScopeRagReranker(properties, RestClient.builder().baseUrl("http://127.0.0.1:1").build());

        assertThatThrownBy(() -> reranker.rerank("query", candidates(), 2))
                .isInstanceOf(RuntimeException.class);
    }
}
