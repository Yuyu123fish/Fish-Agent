package com.yuyu.fishagent.memory.agentstate;

import java.time.Instant;
import java.util.List;

/**
 * Agent 会话状态：记录“在做什么”的过程记忆，与短期内容摘要正交。
 */
public record SessionAgentState(
        String phase,
        List<ActiveTask> activeTasks,
        List<ToolInvocationRecord> recentTools,
        String lastDetectedIntent,
        Instant lastUpdated
) {
    public SessionAgentState {
        phase = phase == null || phase.isBlank() ? "IDLE" : phase;
        activeTasks = activeTasks == null ? List.of() : List.copyOf(activeTasks);
        recentTools = recentTools == null ? List.of() : List.copyOf(recentTools);
        lastUpdated = lastUpdated == null ? Instant.now() : lastUpdated;
    }
}
