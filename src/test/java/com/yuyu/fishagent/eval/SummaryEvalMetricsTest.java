package com.yuyu.fishagent.eval;

import com.yuyu.fishagent.memory.shortterm.StructuredSummary;
import com.yuyu.fishagent.memory.shortterm.TopicSegment;
import com.yuyu.fishagent.memory.shortterm.UserSignals;
import com.yuyu.fishagent.memory.compress.MemoryResponseParser;
import com.yuyu.fishagent.memory.shortterm.KeyExcerpt;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryEvalMetricsTest {

    @Test
    void evaluatesStructuredSummaryAgainstGoldenExpectations() {
        StructuredSummary summary = new StructuredSummary(
                List.of(
                        new TopicSegment("报销流程", "CLOSED", "6/1 提过的服务器迁移方案需保留；报销已完成"),
                        new TopicSegment("Q3规划", "ACTIVE", "继续拆目标")
                ),
                Map.of("person", List.of("张三"), "project", List.of("Q3目标")),
                List.of("等财务回复", "整理 Q3 草案"),
                new UserSignals("backend", "direct", List.of("喜欢短答案"))
        );
        SummaryGoldenSet.Case expected = new SummaryGoldenSet.Case(
                "case-1",
                20,
                List.of(),
                0,
                List.of("张三", "报销流程", "Q3目标", "不存在的实体"),
                List.of(
                        new SummaryGoldenSet.ExpectedTopic("报销流程", "CLOSED"),
                        new SummaryGoldenSet.ExpectedTopic("Q3规划", "ACTIVE"),
                        new SummaryGoldenSet.ExpectedTopic("服务器迁移", "PAUSED")
                ),
                List.of("整理 Q3 草案"),
                List.of("6/1 提过的服务器迁移方案", "丢失的信息")
        );

        SummaryEvalMetrics.Result result = SummaryEvalMetrics.eval(summary, expected);

        assertThat(result.keyEntityRecall()).isEqualTo(0.5);
        assertThat(result.topicStatusAccuracy()).isEqualTo(2.0 / 3.0);
        assertThat(result.earlyInfoRetention()).isEqualTo(0.5);
        assertThat(result.structValid()).isEqualTo(1.0);
    }

    @Test
    void invalidSummaryKeepsMetricsAtZeroWithoutThrowing() {
        SummaryGoldenSet.Case expected = new SummaryGoldenSet.Case(
                "empty",
                20,
                List.of(),
                0,
                List.of("张三"),
                List.of(new SummaryGoldenSet.ExpectedTopic("报销", "ACTIVE")),
                List.of("待办"),
                List.of("早期信息")
        );

        SummaryEvalMetrics.Result result = SummaryEvalMetrics.eval((StructuredSummary) null, expected);

        assertThat(result).isEqualTo(new SummaryEvalMetrics.Result(0.0, 0.0, 0.0, 0.0));
    }

    @Test
    void earlyInfoRetentionIncludesKeyExcerptsContent() {
        StructuredSummary summary = new StructuredSummary(List.of(), Map.of(), List.of(), new UserSignals("", "", List.of()));
        MemoryResponseParser.StructuredCompressionResult compressionResult =
                new MemoryResponseParser.StructuredCompressionResult(
                        summary,
                        List.of(new KeyExcerpt(1, "user", "6/1 提过的服务器迁移方案", "早期关键决策")),
                        null
                );
        SummaryGoldenSet.Case expected = new SummaryGoldenSet.Case(
                "excerpt",
                20,
                List.of(),
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of("6/1 提过的服务器迁移方案")
        );

        SummaryEvalMetrics.Result result = SummaryEvalMetrics.eval(compressionResult, expected);

        assertThat(result.earlyInfoRetention()).isEqualTo(1.0);
    }

    @Test
    void keyEntityRecallUsesExactNormalizedEntityMatch() {
        StructuredSummary summary = new StructuredSummary(
                List.of(),
                Map.of("person", List.of("张三")),
                List.of(),
                new UserSignals("", "", List.of())
        );
        SummaryGoldenSet.Case expected = new SummaryGoldenSet.Case(
                "exact-entity",
                20,
                List.of(),
                0,
                List.of("张"),
                List.of(),
                List.of(),
                List.of()
        );

        SummaryEvalMetrics.Result result = SummaryEvalMetrics.eval(summary, expected);

        assertThat(result.keyEntityRecall()).isZero();
    }
}
