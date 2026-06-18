package com.yuyu.fishagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.memory.compress.MemoryPromptBuilder;
import com.yuyu.fishagent.memory.compress.MemoryResponseParser;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.memory.shortterm.ShortTermMemorySnapshot;
import com.yuyu.fishagent.memory.shortterm.ShortTermMemoryStore;
import com.yuyu.fishagent.memory.shortterm.StructuredSummary;
import com.yuyu.fishagent.memory.shortterm.TopicSegment;
import com.yuyu.fishagent.memory.shortterm.UserSignals;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryCompressionServiceTest {

    @Test
    void recentMessagesKeepsSlidingWindow() {
        List<ChatMessageDTO> history = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> ChatMessageDTO.of("user", "message-" + i))
                .toList();

        List<ChatMessageDTO> recent = MemoryCompressionService.recentMessages(history, 3);

        assertThat(recent)
                .extracting(ChatMessageDTO::getContent)
                .containsExactly("message-3", "message-4", "message-5");
    }

    @Test
    void recentMessagesReturnsEmptyWhenWindowDisabled() {
        List<ChatMessageDTO> history = List.of(ChatMessageDTO.of("user", "message"));

        assertThat(MemoryCompressionService.recentMessages(history, 0)).isEmpty();
    }

    @Test
    void recentMessagesKeepsAllMessagesWhenWindowCoversFullHistory() {
        List<ChatMessageDTO> history = List.of(
                ChatMessageDTO.of("user", "message-1"),
                ChatMessageDTO.of("assistant", "message-2")
        );

        List<ChatMessageDTO> recent = MemoryCompressionService.recentMessages(history, 10);

        assertThat(recent)
                .extracting(ChatMessageDTO::getContent)
                .containsExactly("message-1", "message-2");
    }

    @Test
    void lastMessageCreatedAtUsesLastPositiveTimestamp() {
        List<ChatMessageDTO> history = List.of(
                new ChatMessageDTO("user", "old", 100),
                new ChatMessageDTO("assistant", "missing", 0),
                new ChatMessageDTO("user", "latest", 300)
        );

        assertThat(MemoryCompressionService.lastMessageCreatedAt(history)).isEqualTo(300);
    }

    @Test
    void structuredReconciliationUsesOnlyRecentWindowAndResetsIncrementalCount() {
        CapturingChatModel chatModel = new CapturingChatModel("""
                {
                  "structured_summary": {
                    "activeTopics": [{"topic": "报销", "status": "CLOSED", "summary": "报销流程已完成"}],
                    "keyEntities": {"person": ["张三"]},
                    "pendingIntents": [],
                    "userSignals": {"expertise": "", "communicationStyle": "", "observedPreferences": []}
                  },
                  "key_excerpts": [],
                  "agent_state": {"phase": "IDLE", "activeTasks": [], "lastDetectedIntent": ""}
                }
                """);
        InMemoryShortTermMemoryStore store = new InMemoryShortTermMemoryStore();
        MemoryProperties properties = new MemoryProperties();
        properties.setShortTermWindowSize(2);
        MemoryCompressionService service = new MemoryCompressionService(
                chatModel,
                new MemoryPromptBuilder(new ObjectMapper()),
                new MemoryResponseParser(new ObjectMapper()),
                store,
                properties
        );
        StructuredSummary currentSummary = new StructuredSummary(
                List.of(new TopicSegment("报销", "ACTIVE", "等待财务回复")),
                Map.of("person", List.of("张三")),
                List.of("等财务回复"),
                new UserSignals("", "", List.of())
        );
        List<ChatMessageDTO> fullHistory = List.of(
                new ChatMessageDTO("user", "早期全量历史不应进入对账 prompt", 100),
                new ChatMessageDTO("assistant", "中间消息也不应进入", 200),
                new ChatMessageDTO("user", "财务回复了，报销完成", 300),
                new ChatMessageDTO("assistant", "报销话题关闭", 400)
        );

        service.compressStructured("sid-1", currentSummary, List.of(), fullHistory, 3);

        String promptText = chatModel.lastPrompt.getInstructions().get(1).getText();
        assertThat(promptText)
                .contains("当前摘要")
                .contains("最近窗口对话")
                .contains("财务回复了，报销完成")
                .doesNotContain("早期全量历史不应进入对账 prompt")
                .doesNotContain("中间消息也不应进入");

        assertThat(store.snapshot.incrementalCount()).isZero();
        assertThat(store.snapshot.recentMessages())
                .extracting(ChatMessageDTO::getContent)
                .containsExactly("财务回复了，报销完成", "报销话题关闭");
    }

    private static final class CapturingChatModel implements ChatModel {

        private final String responseText;
        private Prompt lastPrompt;

        private CapturingChatModel(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(responseText))));
        }
    }

    private static final class InMemoryShortTermMemoryStore implements ShortTermMemoryStore {

        private ShortTermMemorySnapshot snapshot;

        @Override
        public void save(String sessionId, ShortTermMemorySnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public ShortTermMemorySnapshot load(String sessionId) {
            return snapshot;
        }

        @Override
        public void clear(String sessionId) {
            snapshot = null;
        }
    }
}
