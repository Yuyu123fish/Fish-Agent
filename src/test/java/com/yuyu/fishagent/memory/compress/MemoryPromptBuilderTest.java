package com.yuyu.fishagent.memory.compress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.memory.shortterm.StructuredSummary;
import com.yuyu.fishagent.memory.shortterm.TopicSegment;
import com.yuyu.fishagent.memory.shortterm.UserSignals;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryPromptBuilderTest {

    private final MemoryPromptBuilder builder = new MemoryPromptBuilder(new ObjectMapper());

    @Test
    void reconciliationPromptUsesCurrentSummaryAndWindowInsteadOfFullHistory() {
        StructuredSummary currentSummary = new StructuredSummary(
                List.of(new TopicSegment("报销", "ACTIVE", "用户正在确认报销流程")),
                Map.of("person", List.of("张三")),
                List.of("等财务回复"),
                new UserSignals("backend", "简洁", List.of("保留关键约束"))
        );
        List<ChatMessageDTO> windowMessages = List.of(
                new ChatMessageDTO("user", "财务已经回复，报销流程完成", 1_780_000_000_000L),
                new ChatMessageDTO("assistant", "我会把报销话题标记为已关闭", 1_780_000_001_000L)
        );

        Prompt prompt = builder.buildReconciliation(currentSummary, windowMessages, 2);
        String content = prompt.getInstructions().get(1).getText();

        assertThat(prompt.getInstructions().get(0).getText())
                .contains("最小修订")
                .contains("\"structured_summary\"")
                .contains("\"key_excerpts\"")
                .contains("\"agent_state\"");
        assertThat(content)
                .contains("## 当前摘要")
                .contains("## 最近窗口对话")
                .contains("报销")
                .contains("财务已经回复，报销流程完成")
                .contains("## window_size\n2")
                .doesNotContain("全量对话历史");
    }
}
