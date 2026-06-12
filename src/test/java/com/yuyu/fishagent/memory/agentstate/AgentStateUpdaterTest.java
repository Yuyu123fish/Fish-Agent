package com.yuyu.fishagent.memory.agentstate;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStateUpdaterTest {

    private final AgentStateUpdater updater = new AgentStateUpdater();

    @Test
    void mergeWithInferredKeepsRuleExtractedToolRecords() {
        SessionAgentState current = new SessionAgentState(
                "IDLE",
                List.of(),
                List.of(new ToolInvocationRecord("web_search", "q", true, null)),
                null,
                Instant.now()
        );
        SessionAgentState inferred = new SessionAgentState(
                "EXECUTING",
                List.of(new ActiveTask("t1", "执行阶段三", "IN_PROGRESS", List.of("建模"), "接入 ChatService", null)),
                List.of(),
                "按方案执行",
                Instant.now()
        );

        SessionAgentState merged = updater.mergeWithInferred(current, inferred);

        assertThat(merged.phase()).isEqualTo("EXECUTING");
        assertThat(merged.activeTasks()).hasSize(1);
        assertThat(merged.recentTools()).extracting(ToolInvocationRecord::toolName).containsExactly("web_search");
        assertThat(merged.lastDetectedIntent()).isEqualTo("按方案执行");
    }
}
