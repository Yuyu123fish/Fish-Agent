package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.rag.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProvenanceBoosterTest {

    @Test
    void authorityAndRecencyCanLiftTrustedFreshHit() {
        RagProperties properties = new RagProperties();
        properties.getProvenance().setEnabled(true);
        properties.getProvenance().setAuthorityAlpha(0.5);
        properties.getProvenance().setRecencyBeta(0.5);
        properties.getProvenance().setRecencyHalfLifeDays(180);

        long now = Instant.parse("2026-06-13T00:00:00Z").toEpochMilli();
        RagRecall.RecallHit stale = new RagRecall.RecallHit(
                "old", "旧资料", 1.0, RagRecall.RecallSource.TEXT, "公开", 0.1,
                Instant.parse("2020-01-01T00:00:00Z").toEpochMilli(), "doc-a", 0);
        RagRecall.RecallHit freshTrusted = new RagRecall.RecallHit(
                "new", "新资料", 1.0, RagRecall.RecallSource.TEXT, "官方", 1.0,
                now, "doc-b", 0);

        List<RagRecall.RecallHit> boosted = new ProvenanceBooster(properties).boost(List.of(stale, freshTrusted), now);

        assertThat(boosted).extracting(RagRecall.RecallHit::id).containsExactly("new", "old");
        assertThat(boosted.get(0).score()).isGreaterThan(boosted.get(1).score());
    }

    @Test
    void disabledBoosterKeepsScoresAndOrder() {
        RagProperties properties = new RagProperties();
        properties.getProvenance().setEnabled(false);
        List<RagRecall.RecallHit> hits = List.of(
                new RagRecall.RecallHit("a", "A", 0.7, RagRecall.RecallSource.TEXT),
                new RagRecall.RecallHit("b", "B", 0.6, RagRecall.RecallSource.TEXT));

        List<RagRecall.RecallHit> boosted = new ProvenanceBooster(properties).boost(hits, 1L);

        assertThat(boosted).isSameAs(hits);
    }
}
