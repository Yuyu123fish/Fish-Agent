package com.yuyu.fishagent.agent.memory.rag.recall;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermRecallHitMergerTest {

    @Test
    void mergeByMaxScoreKeepsHigherScore() {
        List<RagRecall.RecallHit> a = List.of(
                new RagRecall.RecallHit("1", "fact one", 0.5, RagRecall.RecallSource.TEXT),
                new RagRecall.RecallHit("1", "fact one", 0.9, RagRecall.RecallSource.VECTOR)
        );
        List<RagRecall.RecallHit> merged = RagRecall.mergeByMaxScore(List.of(a), 10);
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).score()).isEqualTo(0.9);
    }

    @Test
    void limitsMaxFacts() {
        List<RagRecall.RecallHit> batch = List.of(
                new RagRecall.RecallHit("1", "a", 0.1, RagRecall.RecallSource.TEXT),
                new RagRecall.RecallHit("2", "b", 0.5, RagRecall.RecallSource.TEXT),
                new RagRecall.RecallHit("3", "c", 0.3, RagRecall.RecallSource.TEXT)
        );
        List<RagRecall.RecallHit> merged = RagRecall.mergeByMaxScore(List.of(batch), 2);
        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).content()).isEqualTo("b");
    }
}
