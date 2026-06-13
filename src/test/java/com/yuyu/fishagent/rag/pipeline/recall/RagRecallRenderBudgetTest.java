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

    @Test
    void renderBlockAddsMemoryAgeAndProvenanceLabels() {
        List<RagRecall.RecallHit> hits = List.of(
                new RagRecall.RecallHit("m1", "用户住在上海", 0.9, RagRecall.RecallSource.TEXT,
                        "记忆", 0.8, System.currentTimeMillis(), null, null),
                new RagRecall.RecallHit("k1", "官方知识", 0.8, RagRecall.RecallSource.VECTOR,
                        "官方", 1.0, 1_780_000_000_000L, "doc-1", 0)
        );

        String block = RagRecall.DefaultAugmentation.renderBlock(hits, 200);

        assertThat(block).contains("若多条参考事实彼此冲突");
        assertThat(block).contains("[记忆 · ");
        assertThat(block).contains("[来源:官方·2026-");
    }

    @Test
    void renderBlockProtectsCenterHitWhenExpandedNeighborsExceedBudget() {
        RagRecall.RecallHit expanded = new RagRecall.RecallHit(
                "k1",
                "核心命中 " + "邻片噪声".repeat(200),
                0.9,
                RagRecall.RecallSource.TEXT,
                "官方",
                1.0,
                1_780_000_000_000L,
                "doc-1",
                3);

        String block = RagRecall.DefaultAugmentation.renderBlock(List.of(expanded), 30);

        assertThat(block).contains("核心命中");
    }
}
