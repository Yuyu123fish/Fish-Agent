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

    /** 各工具可覆盖单结果预算，key 为工具名。 */
    private Map<String, Integer> budgetOverrides = new HashMap<>();

    public int budgetTokensFor(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Math.max(1, budgetTokens);
        }
        return Math.max(1, budgetOverrides.getOrDefault(toolName, budgetTokens));
    }
}
