package com.yuyu.fishagent.rag.tracing;

import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagQualityLoggerTest {

    @Test
    void toInjectedFacts_mapsIdSourceLabelAndScore() {
        RagRecall.RecallHit card = new RagRecall.RecallHit("card:1", "知识卡片《JVM》:…",
                0.9, RagRecall.RecallSource.VECTOR, "图谱", 0.6, null, null, null, null);
        RagRecall.RecallHit mem = new RagRecall.RecallHit("mem:2", "记忆",
                0.5, RagRecall.RecallSource.TEXT, null, null, 123L, null, null, null);

        List<RagTraceDocument.InjectedFact> facts = RagQualityLogger.toInjectedFacts(List.of(card, mem));

        assertThat(facts).hasSize(2);
        assertThat(facts.get(0).getId()).isEqualTo("card:1");
        assertThat(facts.get(0).getSourceLabel()).isEqualTo("图谱");
        assertThat(facts.get(0).getScore()).isEqualTo(0.9);
        // effectiveSourceLabel() 对 null 回退为「公开」
        assertThat(facts.get(1).getId()).isEqualTo("mem:2");
        assertThat(facts.get(1).getSourceLabel()).isEqualTo("公开");
        assertThat(facts.get(1).getScore()).isEqualTo(0.5);
    }

    @Test
    void toInjectedFacts_emptyOrNullReturnsEmpty() {
        assertThat(RagQualityLogger.toInjectedFacts(null)).isEmpty();
        assertThat(RagQualityLogger.toInjectedFacts(List.of())).isEmpty();
    }
}
