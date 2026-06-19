package com.yuyu.fishagent.memory.compress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.common.util.TextTruncator;
import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.memory.shortterm.StructuredSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 短期记忆摘要 Prompt 构建器。
 * <p>只负责把对话历史转换成短期摘要输入，不提取长期事实，避免摘要任务污染 ES 长期记忆。</p>
 */
@Component
@RequiredArgsConstructor
public class MemoryPromptBuilder {

    private static final int MAX_COMPRESSION_MSG_LEN = 1500;
    private final ObjectMapper objectMapper;

    private static final String INCREMENTAL_SYSTEM_PROMPT = """
            你是 Fish-Agent 的短期记忆摘要专家。

            请基于当前结构化摘要和本轮新增对话，增量更新短期记忆。

            重要约束：
            - 以下最后 window_size 条消息保留在滑动窗口中，无需在摘要中覆盖。
            - 在现有摘要结构上增量更新，不要从零重写。
            - keyExcerpts 只保留用户明确约束、关键决策点、排障结论或明显偏好。
            - 同时推断 agent_state，但不要额外解释。
            - 只输出严格 JSON，不要输出 Markdown、解释或代码块。

            输出格式：
            {
              "structured_summary": {
                "activeTopics": [{"topic": "话题名", "status": "ACTIVE|PAUSED|CLOSED", "summary": "摘要"}],
                "keyEntities": {"类别": ["实体"]},
                "pendingIntents": ["待办"],
                "userSignals": {"expertise": "", "communicationStyle": "", "observedPreferences": []}
              },
              "key_excerpts": [{"turnIndex": 1, "role": "user", "content": "原文", "reason": "原因"}],
              "agent_state": {"phase": "EXPLORING|EXECUTING|REVIEWING|IDLE", "activeTasks": [], "lastDetectedIntent": ""}
            }
            """;

    private static final String RECONCILIATION_SYSTEM_PROMPT = """
            你是 Fish-Agent 的短期记忆摘要专家。

            请基于当前结构化摘要和最近窗口对话，执行短期记忆对账校准。

            你的目标是批判性比对“当前摘要”与“最近窗口对话”，做最小修订，而不是从零重写。

            重要约束：
            - 以下最后 window_size 条消息保留在滑动窗口中，无需在摘要中覆盖。
            - 保留当前摘要中未被最近窗口推翻的内容。
            - 如果窗口显示话题已解决或切换，将 ACTIVE 调整为 CLOSED 或 PAUSED。
            - 如果窗口出现新的关键实体，补充到 keyEntities。
            - 如果窗口显示待办已完成，从 pendingIntents 移除。
            - 如果摘要与窗口冲突，以窗口为准做矛盾修正。
            - 合并重复话题和重复实体，避免冗余膨胀。
            - keyExcerpts 最多保留 5 条关键原文片段。
            - 同时推断 agent_state，但不要额外解释。
            - 只输出严格 JSON，不要输出 Markdown、解释或代码块。

            输出格式：
            {
              "structured_summary": {
                "activeTopics": [{"topic": "话题名", "status": "ACTIVE|PAUSED|CLOSED", "summary": "摘要"}],
                "keyEntities": {"类别": ["实体"]},
                "pendingIntents": ["待办"],
                "userSignals": {"expertise": "", "communicationStyle": "", "observedPreferences": []}
              },
              "key_excerpts": [{"turnIndex": 1, "role": "user", "content": "原文", "reason": "原因"}],
              "agent_state": {"phase": "EXPLORING|EXECUTING|REVIEWING|IDLE", "activeTasks": [], "lastDetectedIntent": ""}
            }
            """;

    private static final String CALIBRATION_SYSTEM_PROMPT = """
            你是 Fish-Agent 的短期记忆摘要专家。

            请阅读全量对话历史，从零构建结构化短期记忆。这是 eval 全量基线，不依赖旧摘要。

            重要约束：
            - 以下最后 window_size 条消息保留在滑动窗口中，无需在摘要中覆盖。
            - 从全量历史提取活跃话题、关键实体、待办意图、用户信号。
            - keyExcerpts 最多保留 5 条关键原文片段。
            - 同时推断 agent_state，但不要额外解释。
            - 只输出严格 JSON，不要输出 Markdown、解释或代码块。

            输出格式与增量模式一致。
            """;

    /**
     * 增量压缩：基于现有摘要 + 窗口外新增消息。
     */
    public Prompt buildIncremental(StructuredSummary currentSummary,
                                   List<ChatMessageDTO> newMessages,
                                   int windowSize) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>(2);
        messages.add(new SystemMessage(INCREMENTAL_SYSTEM_PROMPT));
        StringBuilder userContent = new StringBuilder();
        userContent.append("## 当前摘要\n")
                .append(formatStructuredSummary(currentSummary)).append("\n\n")
                .append("## 本轮新增对话\n")
                .append(formatHistory(newMessages)).append("\n\n")
                .append("## window_size\n").append(windowSize);
        messages.add(new UserMessage(userContent.toString()));
        return new Prompt(messages);
    }

    /**
     * 对账校准：基于当前摘要和最近窗口做最小修订，避免把全量历史拼进 prompt。
     *
     * @param currentSummary 当前结构化摘要，窗口未推翻的内容应被模型保留
     * @param windowMessages 最近滑动窗口消息，调用方必须先完成窗口裁剪
     * @param windowSize 当前窗口大小，仅用于提示模型理解上下文保留边界
     * @return 面向记忆模型的对账 Prompt
     */
    public Prompt buildReconciliation(StructuredSummary currentSummary,
                                      List<ChatMessageDTO> windowMessages,
                                      int windowSize) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>(2);
        messages.add(new SystemMessage(RECONCILIATION_SYSTEM_PROMPT));
        StringBuilder userContent = new StringBuilder();
        userContent.append("## 当前摘要\n")
                .append(formatStructuredSummary(currentSummary)).append("\n\n")
                .append("## 最近窗口对话\n")
                .append(formatHistory(windowMessages == null ? List.of() : windowMessages)).append("\n\n")
                .append("## window_size\n").append(windowSize);
        messages.add(new UserMessage(userContent.toString()));
        return new Prompt(messages);
    }

    /**
     * 全量校准：从全量历史重新构建结构化摘要。
     *
     * <p>主压缩链路已改用 {@link #buildReconciliation(StructuredSummary, List, int)}。
     * 该方法保留给 summary eval 作为全量基线，便于用数据判断是否需要阶段 3 兜底。</p>
     */
    public Prompt buildCalibration(List<ChatMessageDTO> fullHistory, int windowSize) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>(2);
        messages.add(new SystemMessage(CALIBRATION_SYSTEM_PROMPT));
        StringBuilder userContent = new StringBuilder();
        userContent.append("## 全量对话历史\n")
                .append(formatHistory(fullHistory)).append("\n\n")
                .append("## window_size\n").append(windowSize);
        messages.add(new UserMessage(userContent.toString()));
        return new Prompt(messages);
    }

    /**
     * 将结构化摘要序列化为 Prompt 中稳定可读的 JSON。
     */
    private String formatStructuredSummary(StructuredSummary summary) {
        if (summary == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            return "null";
        }
    }

    /**
     * 把 DTO 历史转成稳定的文本结构，降低模型误读角色、时间和消息边界的概率。
     * 使用类 YAML 格式，确保多行内容和特殊字符不会破坏结构解析
     */
    private String formatHistory(List<ChatMessageDTO> chatHistory) {
        StringBuilder sb = new StringBuilder("chat_history:\n");
        for (ChatMessageDTO message : chatHistory) {
            String role = message.getRole() == null ? "unknown" : message.getRole();
            String content = message.getContent() == null ? "" : message.getContent();
            if (content.length() > MAX_COMPRESSION_MSG_LEN) {
                content = TextTruncator.headTailCompress(content, 400, 400);
            }
            // 将时间戳转换为可读的 ISO-8601 格式
            String createdAt = message.getCreatedAt() > 0
                    ? Instant.ofEpochMilli(message.getCreatedAt()).toString()
                    : "unknown-time";
            sb.append("- role: ").append(role).append('\n')
                    .append("  created_at: ").append(createdAt).append('\n')
                    .append("  content: |-\n")  // 使用 YAML 块标记保持多行内容
                    .append(indent(content)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 缩进多行消息内容，使其在 {@code content: |-} 块中保持可读且不破坏结构。
     */
    private String indent(String content) {
        String[] lines = content.split("\\R", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("    ").append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
