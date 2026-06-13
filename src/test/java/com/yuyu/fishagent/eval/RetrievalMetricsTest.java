package com.yuyu.fishagent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalMetricsTest {

    @Test
    void computesPrecisionMrrAndNdcgAtK() {
        List<String> ranked = List.of("a", "b", "c", "d");
        Map<String, Integer> relevance = Map.of("a", 0, "b", 3, "c", 1, "d", 0);

        RetrievalMetrics.Result result = RetrievalMetrics.evaluate(ranked, relevance, 3);

        assertThat(result.precisionAtK()).isEqualTo(2.0 / 3.0);
        assertThat(result.mrr()).isEqualTo(0.5);
        assertThat(result.ndcgAtK()).isGreaterThan(0.6).isLessThan(1.0);
    }

    @Test
    void emptyRankingHasZeroMetrics() {
        RetrievalMetrics.Result result = RetrievalMetrics.evaluate(List.of(), Map.of("a", 1), 5);

        assertThat(result.precisionAtK()).isZero();
        assertThat(result.mrr()).isZero();
        assertThat(result.ndcgAtK()).isZero();
    }
}
