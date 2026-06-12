package com.yuyu.fishagent.memory.agentstate;

import com.alibaba.cloud.ai.graph.NodeOutput;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则驱动的 Agent 状态更新器。
 * 从 ReAct 节点输出中提取确定性工具调用信息，并与压缩阶段 LLM 推断状态合并。
 */
@Component
public class AgentStateUpdater {

    /**
     * 根据本轮 ReAct 节点输出更新近期工具调用记录。
     */
    public SessionAgentState updateToolRecords(SessionAgentState current, List<NodeOutput> nodes) {
        if (current == null) {
            current = new SessionAgentState("IDLE", List.of(), List.of(), null, Instant.now());
        }
        if (nodes == null || nodes.isEmpty()) {
            return current;
        }

        List<ToolInvocationRecord> tools = new ArrayList<>(current.recentTools());
        for (NodeOutput node : nodes) {
            if (node != null && isToolRelatedNode(node)) {
                tools.add(extractToolRecord(node));
            }
        }
        if (tools.size() > 20) {
            tools = new ArrayList<>(tools.subList(tools.size() - 20, tools.size()));
        }

        return new SessionAgentState(
                current.phase(),
                current.activeTasks(),
                tools,
                current.lastDetectedIntent(),
                Instant.now()
        );
    }

    /**
     * 将 LLM 推断出的状态合并到当前状态；工具记录保留规则提取结果，因为它更贴近真实调用。
     */
    public SessionAgentState mergeWithInferred(SessionAgentState current, SessionAgentState inferredState) {
        if (current == null) {
            current = new SessionAgentState("IDLE", List.of(), List.of(), null, Instant.now());
        }
        if (inferredState == null) {
            return current;
        }

        return new SessionAgentState(
                inferredState.phase() != null ? inferredState.phase() : current.phase(),
                inferredState.activeTasks() != null && !inferredState.activeTasks().isEmpty()
                        ? inferredState.activeTasks() : current.activeTasks(),
                current.recentTools(),
                inferredState.lastDetectedIntent() != null
                        ? inferredState.lastDetectedIntent() : current.lastDetectedIntent(),
                Instant.now()
        );
    }

    private boolean isToolRelatedNode(NodeOutput node) {
        String className = node.getClass().getSimpleName().toLowerCase();
        String nodeText = String.valueOf(node).toLowerCase();
        return className.contains("tool")
                || className.contains("function")
                || knownTools().stream().anyMatch(nodeText::contains);
    }

    private ToolInvocationRecord extractToolRecord(NodeOutput node) {
        String nodeText = String.valueOf(node);
        String lower = nodeText.toLowerCase();
        boolean failed = lower.contains("error") || lower.contains("exception") || lower.contains("failed");
        return new ToolInvocationRecord(
                extractToolName(lower),
                truncate(nodeText, 100),
                !failed,
                failed ? truncate(nodeText, 200) : null
        );
    }

    private String extractToolName(String lowerText) {
        return knownTools().stream()
                .filter(lowerText::contains)
                .findFirst()
                .orElse("unknown");
    }

    private List<String> knownTools() {
        return List.of(
                "web_fetch", "web_search", "file_read", "file_write", "calculator",
                "get_current_datetime", "send_mail", "bocha_search", "amap_weather", "amap_geo"
        );
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }
}
