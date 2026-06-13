package com.yuyu.fishagent.agent.tool.result;

import com.yuyu.fishagent.common.util.TextTruncator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具结果摘要器。
 *
 * <p>摘要只作为大结果治理的中档路径：失败时由调用方回落到预算截断，绝不影响工具调用主链路。</p>
 */
@Slf4j
@Component
public class ToolResultSummarizer {

    private final ObjectProvider<ChatModel> chatModelProvider;

    public ToolResultSummarizer(@Qualifier("memoryChatModel") ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    public String summarize(String toolName, String input, String result, int budgetTokens) {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null || result == null || result.isBlank()) {
            return null;
        }
        try {
            String compressed = TextTruncator.headTailCompress(result, 12_000, 12_000);
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage("""
                            你是工具结果压缩器。请忠实摘要工具输出，保留错误、数字、路径、URL、时间、最终结论和可执行线索。
                            不要编造，不要引入工具输出外的信息。输出中文，结构清晰，控制在目标 token 预算内。
                            """),
                    new UserMessage("""
                            工具名：%s
                            工具输入：
                            %s

                            目标预算：不超过 %d tokens。

                            工具原始输出（已做头尾压缩，仅用于摘要）：
                            %s
                            """.formatted(toolName, input == null ? "" : input, Math.max(1, budgetTokens), compressed))
            ));
            return model.call(prompt).getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[ToolResultSummarizer] 工具结果摘要失败 tool={}: {}", toolName, e.getMessage());
            return null;
        }
    }
}
