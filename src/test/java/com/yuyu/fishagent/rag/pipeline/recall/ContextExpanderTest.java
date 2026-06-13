package com.yuyu.fishagent.rag.pipeline.recall;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextExpanderTest {

    @Test
    void mergeNeighborsOrdersByChunkIndexAndDeduplicatesHit() {
        RagRecall.RecallHit center = new RagRecall.RecallHit(
                "doc-1:1", "中间", 0.9, RagRecall.RecallSource.VECTOR, "用户", 0.7, 1L, "doc-1", 1);
        RagRecall.RecallHit before = new RagRecall.RecallHit(
                "doc-1:0", "前文", 0.1, RagRecall.RecallSource.TEXT, "用户", 0.7, 1L, "doc-1", 0);
        RagRecall.RecallHit duplicateCenter = new RagRecall.RecallHit(
                "doc-1:1", "中间", 0.1, RagRecall.RecallSource.TEXT, "用户", 0.7, 1L, "doc-1", 1);
        RagRecall.RecallHit after = new RagRecall.RecallHit(
                "doc-1:2", "后文", 0.1, RagRecall.RecallSource.TEXT, "用户", 0.7, 1L, "doc-1", 2);

        RagRecall.RecallHit merged = ContextExpander.mergeNeighbors(center, List.of(after, duplicateCenter, before));

        assertThat(merged.content()).isEqualTo("前文\n中间\n后文");
        assertThat(merged.score()).isEqualTo(0.9);
        assertThat(merged.docId()).isEqualTo("doc-1");
        assertThat(merged.chunkIndex()).isEqualTo(1);
    }
}
