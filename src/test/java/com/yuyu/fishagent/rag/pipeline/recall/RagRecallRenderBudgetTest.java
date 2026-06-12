package com.yuyu.fishagent.rag.pipeline.recall;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagRecallRenderBudgetTest {

    @Test
    void renderBlockStopsBeforeExceedingTokenBudget() {
        List<RagRecall.RecallHit> hits = List.of(
                new RagRecall.RecallHit("1", "第一条事实".repeat(12), 0.9, RagRecall.RecallSource.TEXT),
                new RagRecall.RecallHit("2", "第二条事实".repeat(12), 0.8, RagRecall.RecallSource.VECTOR)
        );

        String block = RagRecall.DefaultAugmentation.renderBlock(hits, 60);

        assertThat(block).contains("第一条事实");
        assertThat(block).doesNotContain("第二条事实");
    }

    @Test
    void renderBlockFallsBackToCharacterLimitWhenTokenBudgetIsNotPositive() {
        List<RagRecall.RecallHit> hits = List.of(
                new RagRecall.RecallHit("1", "short fact", 0.9, RagRecall.RecallSource.TEXT)
        );

        String block = RagRecall.DefaultAugmentation.renderBlock(hits, 0);

        assertThat(block).contains("short fact");
    }
}
