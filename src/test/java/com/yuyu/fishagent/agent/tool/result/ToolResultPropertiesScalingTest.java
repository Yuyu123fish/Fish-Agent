package com.yuyu.fishagent.agent.tool.result;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具结果预算随模型上下文窗口自缩放（方案 B）：有效值 = max(绝对下限, 窗口×分数)。
 *
 * <p>核心约束：窗口未知(≤0)或关闭开关时退回绝对下限 —— 这既保证小窗模型不回退，
 * 也让不带窗口的旧单测零行为变化。</p>
 */
class ToolResultPropertiesScalingTest {

    private static final int WINDOW_1M = 1_048_576;
    private static final int WINDOW_QWEN = 131_072;

    @Test
    void budgetScalesUpAtOneMillionWindow() {
        ToolResultProperties p = new ToolResultProperties();

        int budget = p.effectiveBudgetTokens(null, WINDOW_1M);

        assertThat(budget).isEqualTo((int) Math.floor(WINDOW_1M * 0.03));
        assertThat(budget).isGreaterThanOrEqualTo(30_000); // 1M 下普通网页/搜索结果放行不截断
    }

    @Test
    void summarizeAndScratchThresholdsScaleUpAtOneMillionWindow() {
        ToolResultProperties p = new ToolResultProperties();

        assertThat(p.effectiveSummarizeThreshold(WINDOW_1M))
                .isEqualTo((int) Math.floor(WINDOW_1M * 0.12))
                .isGreaterThanOrEqualTo(120_000); // 只摘要真正巨型结果
        assertThat(p.effectiveScratchThreshold(WINDOW_1M))
                .isEqualTo((int) Math.floor(WINDOW_1M * 0.25))
                .isGreaterThanOrEqualTo(256_000); // 只对巨型日志卸载
    }

    @Test
    void smallWindowFallsBackToAbsoluteFloorNoRegression() {
        ToolResultProperties p = new ToolResultProperties();
        // qwen 131K：3% = 3932 < floor 4096 → floor 生效，与今天一致
        assertThat(p.effectiveBudgetTokens(null, WINDOW_QWEN)).isEqualTo(4096);
    }

    @Test
    void unknownWindowFallsBackToAbsoluteFloor() {
        ToolResultProperties p = new ToolResultProperties();
        assertThat(p.effectiveBudgetTokens(null, 0)).isEqualTo(4096);
        assertThat(p.effectiveBudgetTokens(null, -1)).isEqualTo(4096);
    }

    @Test
    void disablingWindowRelativeKeepsAbsoluteFloorsEvenAtOneMillion() {
        ToolResultProperties p = new ToolResultProperties();
        p.setWindowRelativeEnabled(false);

        assertThat(p.effectiveBudgetTokens(null, WINDOW_1M)).isEqualTo(4096);
        assertThat(p.effectiveSummarizeThreshold(WINDOW_1M)).isEqualTo(8192);
        assertThat(p.effectiveScratchThreshold(WINDOW_1M)).isEqualTo(20_480);
    }

    @Test
    void explicitPerToolOverrideIsAbsoluteAndNotScaled() {
        // 运营在 budget-overrides 里设的 per-tool 硬上限是刻意选择，不参与缩放
        ToolResultProperties p = new ToolResultProperties();
        p.setBudgetOverrides(Map.of("web_fetch", 8000));

        assertThat(p.effectiveBudgetTokens("web_fetch", WINDOW_1M)).isEqualTo(8000);
        // 未显式覆盖的工具仍按默认缩放
        assertThat(p.effectiveBudgetTokens("web_search", WINDOW_1M))
                .isEqualTo((int) Math.floor(WINDOW_1M * 0.03));
    }
}
