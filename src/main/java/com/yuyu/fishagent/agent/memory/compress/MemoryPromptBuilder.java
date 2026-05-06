package com.yuyu.fishagent.agent.memory.compress;

import com.yuyu.fishagent.dto.ChatMessageDTO;
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
public class MemoryPromptBuilder {

    private static final String SYSTEM_PROMPT = """
            你是 Fish-Agent 的短期记忆摘要专家。你的任务是阅读 chat_history，
            压缩出可继续对话的短期摘要。

            提取规则：
            1. short_term_summary 只保留本轮上下文继续对话所需的信息，避免复述无关细节。
            2. 本流程不负责长期记忆录入，long_term_facts 必须始终返回空数组。
            3. 不要把身份、偏好等长期事实写入 long_term_facts，它们由主动录入链路单独处理。
            4. 只输出严格 JSON，不要输出 Markdown、解释或代码块。

            输出格式：
            {
              "short_term_summary": "string",
              "long_term_facts": []
            }
            """;

    /**
     * 构建记忆压缩模型的输入。
     *
     * @param chatHistory 原始对话历史，按时间正序排列
     * @return 包含记忆专家系统提示词与格式化历史记录的 Prompt
     */
    public Prompt build(List<ChatMessageDTO> chatHistory) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>(2);
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.add(new UserMessage(formatHistory(chatHistory)));
        return new Prompt(messages);
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
