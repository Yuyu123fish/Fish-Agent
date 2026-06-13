package com.yuyu.fishagent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalRunnerTest {

    @Test
    void provenanceAwareRankingBeatsBaselineOnGoldenCase() {
        GoldenSet.Candidate official = new GoldenSet.Candidate(
                "official", "官方政策", 0.5, "官方", 1.0, 1_780_000_000_000L, 3);
        GoldenSet.Candidate stale = new GoldenSet.Candidate(
                "stale", "旧公开资料", 0.5, "公开", 0.2, 1_600_000_000_000L, 0);
        GoldenSet.Case item = new GoldenSet.Case("q1", "政策是什么", List.of(stale, official));

        EvalReport report = new EvalRunner().run(List.of(item), 5);

        assertThat(report.caseCount()).isEqualTo(1);
        assertThat(report.provenance().ndcgAtK()).isGreaterThan(report.baseline().ndcgAtK());
        assertThat(report.provenance().mrr()).isEqualTo(1.0);
    }
}
