package com.yuyu.fishagent.agent.tool.result;

import com.yuyu.fishagent.common.util.TokenEstimator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultBudgeterTest {

    @Test
    void keepsUnchangedWhenWithinBudget() {
        ToolResultBudgeter budgeter = new ToolResultBudgeter();

        ToolResultBudgeter.BudgetedResult result = budgeter.fit("short result", 100, "truncated");

        assertThat(result.content()).isEqualTo("short result");
        assertThat(result.disposition()).isEqualTo("unchanged");
    }

    @Test
    void truncatesWithHeadAndTailWithinBudget() {
        ToolResultBudgeter budgeter = new ToolResultBudgeter();
        String text = "HEAD-" + "x".repeat(2_000) + "-TAIL";

        ToolResultBudgeter.BudgetedResult result = budgeter.fit(text, 120, "truncated");

        assertThat(TokenEstimator.estimate(result.content())).isLessThanOrEqualTo(120);
        assertThat(result.content()).contains("HEAD-");
        assertThat(result.content()).contains("-TAIL");
        assertThat(result.content()).contains("工具结果已截断");
        assertThat(result.disposition()).isEqualTo("truncated");
    }

    @Test
    void truncatesChineseTextWithinTokenBudget() {
        ToolResultBudgeter budgeter = new ToolResultBudgeter();
        String text = "开头" + "这是中文工具结果".repeat(900) + "结尾";

        ToolResultBudgeter.BudgetedResult result = budgeter.fit(text, 300, "truncated");

        assertThat(result.disposition()).isEqualTo("truncated");
        assertThat(TokenEstimator.estimate(result.content())).isLessThanOrEqualTo(300);
        assertThat(result.content()).contains("开头");
        assertThat(result.content()).contains("结尾");
    }
}
