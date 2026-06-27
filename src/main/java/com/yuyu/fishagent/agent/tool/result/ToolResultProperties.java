package com.yuyu.fishagent.agent.tool.result;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具结果治理配置，对应 {@code fish.tool.result.*}。
 *
 * <p>旧的 {@code fish.tools.max-result-chars} 仍保留给兼容场景；v6.2 以后主治理链路按 token
 * 预算工作，避免单个工具结果挤爆整轮模型上下文。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.tool.result")
public class ToolResultProperties {

    /** A：单个工具结果默认 token 上限。 */
    private int budgetTokens = 4096;

    /** B：超过该 token 数时尝试摘要。 */
    private int summarizeThresholdTokens = 8192;

    /** B：是否启用 LLM 摘要；关闭后超阈值结果直接进入 A 或 C。 */
    private boolean summarizeEnabled = true;

    /** C：超过该 token 数时进入单轮 scratch store。 */
    private int scratchLargeThresholdTokens = 20_480;

    /** C：是否启用 scratch 检索式注入。 */
    private boolean scratchEnabled = true;

    /** C：单轮 search_large_result 最大调用次数。 */
    private int scratchSearchMaxCalls = 5;

    /** C：大结果分片 token 预算。 */
    private int scratchChunkTokens = 900;

    /** C：首次注入给 Agent 的候选片段数量。 */
    private int scratchInjectTopK = 3;

    /** C：scratch key TTL，通常覆盖单轮生命周期即可。 */
    private Duration scratchTtl = Duration.ofMinutes(30);

    // ── 窗口比例自缩放（方案 B）：有效预算随模型上下文窗口放大 ──
    // 动机：绝对阈值是为 V3 的 64K 窗口调的；V4 迁到 1M 后未同步，导致正常网页/搜索结果
    // （5–15K token）也被有损截断/摘要。改为 max(绝对下限, 窗口×分数) 后，大窗下正常结果放行，
    // 小窗/未知窗退回绝对下限（今天的行为），零回归。
    /** 是否启用按窗口比例缩放；关闭则一律用绝对下限。 */
    private boolean windowRelativeEnabled = true;

    /** 单结果预算占窗口的分数（1M 下约 30K，正常工具结果放行不截断）。 */
    private double budgetFraction = 0.03;

    /** 摘要阈值占窗口的分数（1M 下约 126K，只对巨型结果有损压缩）。 */
    private double summarizeFraction = 0.12;

    /** scratch 卸载阈值占窗口的分数（1M 下约 256K，只对巨型日志走 re-search）。 */
    private double scratchFraction = 0.25;

    /** 各工具可覆盖单结果预算，key 为工具名。 */
    private Map<String, Integer> budgetOverrides = new HashMap<>();

    /**
     * 当前窗口下的有效单结果预算。
     *
     * <p>优先级：显式 per-tool override（运营刻意设的硬上限，不缩放）&gt; 按窗口缩放的默认值。
     * 窗口未知(≤0)或关闭缩放时，退回绝对下限 {@link #budgetTokens}。</p>
     *
     * @param toolName      工具名，可为 null
     * @param contextWindow 当前模型有效上下文窗口（token），≤0 表示未知
     */
    public int effectiveBudgetTokens(String toolName, int contextWindow) {
        if (toolName != null && !toolName.isBlank() && budgetOverrides.containsKey(toolName)) {
            return Math.max(1, budgetOverrides.get(toolName));
        }
        return scaledOrFloor(budgetTokens, budgetFraction, contextWindow);
    }

    /**
     * 当前窗口下的有效摘要阈值。
     *
     * @param contextWindow 当前模型有效上下文窗口（token），≤0 表示未知
     */
    public int effectiveSummarizeThreshold(int contextWindow) {
        return scaledOrFloor(summarizeThresholdTokens, summarizeFraction, contextWindow);
    }

    /**
     * 当前窗口下的有效 scratch 卸载阈值。
     *
     * @param contextWindow 当前模型有效上下文窗口（token），≤0 表示未知
     */
    public int effectiveScratchThreshold(int contextWindow) {
        return scaledOrFloor(scratchLargeThresholdTokens, scratchFraction, contextWindow);
    }

    /**
     * max(绝对下限, 窗口×分数)；关闭缩放或窗口未知时仅返回下限。
     */
    private int scaledOrFloor(int floor, double fraction, int contextWindow) {
        int safeFloor = Math.max(1, floor);
        if (!windowRelativeEnabled || contextWindow <= 0) {
            return safeFloor;
        }
        double fractionClamped = Math.max(0.0, fraction);
        return Math.max(safeFloor, (int) Math.floor(contextWindow * fractionClamped));
    }
}
