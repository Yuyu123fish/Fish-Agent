package com.yuyu.fishagent.memory.agentstate;

import java.util.List;

/**
 * 活跃任务：追踪 Agent 正在执行的多步骤任务进度。
 */
public record ActiveTask(
        String taskId,
        String description,
        String status,
        List<String> completedSteps,
        String currentStep,
        String blockedReason
) {
    public ActiveTask {
        completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
    }
}
