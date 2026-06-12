package com.yuyu.fishagent.memory.agentstate;

/**
 * 工具调用记录：由 ReAct 节点输出规则提取，无需额外 LLM 调用。
 */
public record ToolInvocationRecord(
        String toolName,
        String inputSummary,
        boolean succeeded,
        String failureReason
) {
}
