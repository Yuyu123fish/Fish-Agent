package com.yuyu.fishagent.rag.pipeline.fusion;

import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RagScoreFusionTest {

    private static RagRecall.RecallHit hit(String id, double score, RagRecall.RecallSource source) {
        return new RagRecall.RecallHit(id, "content-" + id, score, source);
    }

    @Test
    void fuseByRrfRanksByReciprocalRankNotRawScore() {
        List<RagRecall.RecallHit> textLeg = List.of(
                hit("A", 12.0, RagRecall.RecallSource.TEXT),
                hit("B", 8.0, RagRecall.RecallSource.TEXT));
        List<RagRecall.RecallHit> vectorLeg = List.of(
                hit("B", 0.90, RagRecall.RecallSource.VECTOR),
                hit("A", 0.30, RagRecall.RecallSource.VECTOR));

        List<RagRecall.RecallHit> fused = RagScoreFusion.fuseByRrf(List.of(textLeg, vectorLeg), 60, 10);

        double expected = 1.0 / 61 + 1.0 / 62;
        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).score()).isCloseTo(expected, within(1e-9));
        assertThat(fused.get(1).score()).isCloseTo(expected, within(1e-9));
    }

    @Test
    void fuseByRrfDedupsByKeyAndKeepsBestRepresentativeContent() {
        List<RagRecall.RecallHit> textLeg = List.of(
                new RagRecall.RecallHit("X", "lower", 5.0, RagRecall.RecallSource.TEXT));
        List<RagRecall.RecallHit> vectorLeg = List.of(
                new RagRecall.RecallHit("X", "higher", 0.7, RagRecall.RecallSource.VECTOR));

        List<RagRecall.RecallHit> fused = RagScoreFusion.fuseByRrf(List.of(textLeg, vectorLeg), 60, 10);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).content()).isEqualTo("lower");
        assertThat(fused.get(0).score()).isCloseTo(2.0 / 61, within(1e-9));
    }

    @Test
    void fuseByRrfLimitsToPoolSize() {
        List<RagRecall.RecallHit> group = List.of(
                hit("A", 9.0, RagRecall.RecallSource.TEXT),
                hit("B", 8.0, RagRecall.RecallSource.TEXT),
                hit("C", 7.0, RagRecall.RecallSource.TEXT));

        List<RagRecall.RecallHit> fused = RagScoreFusion.fuseByRrf(List.of(group), 60, 2);

        assertThat(fused).extracting(RagRecall.RecallHit::id).containsExactly("A", "B");
    }

    @Test
    void fuseByRrfSkipsBlankHitsAndNullGroups() {
        List<RagRecall.RecallHit> group = new ArrayList<>();
        group.add(hit("A", 1.0, RagRecall.RecallSource.TEXT));
        group.add(new RagRecall.RecallHit("", "   ", 5.0, RagRecall.RecallSource.TEXT));
        List<List<RagRecall.RecallHit>> batches = new ArrayList<>();
        batches.add(null);
        batches.add(group);

        List<RagRecall.RecallHit> fused = RagScoreFusion.fuseByRrf(batches, 60, 10);

        assertThat(fused).singleElement().extracting(RagRecall.RecallHit::id).isEqualTo("A");
    }
}
