package com.yuyu.fishagent.chat.budget;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextWindowTrimmerTest {

    @Test
    void keepsNewestMessagesWithinBudget() {
        List<ChatMessageDTO> messages = List.of(
                ChatMessageDTO.of("user", "older question"),
                ChatMessageDTO.of("assistant", "older answer"),
                ChatMessageDTO.of("user", "latest question"),
                ChatMessageDTO.of("assistant", "latest answer")
        );

        List<ChatMessageDTO> trimmed = ContextWindowTrimmer.trimMessagesByBudget(messages, 8);

        assertThat(trimmed)
                .extracting(ChatMessageDTO::getContent)
                .containsExactly("latest question", "latest answer");
    }

    @Test
    void doesNotStartWithAssistantWhenPairBoundaryCanBeAdjusted() {
        List<ChatMessageDTO> messages = List.of(
                ChatMessageDTO.of("user", "first message"),
                ChatMessageDTO.of("assistant", "context answer"),
                ChatMessageDTO.of("user", "final")
        );

        List<ChatMessageDTO> trimmed = ContextWindowTrimmer.trimMessagesByBudget(messages, 6);

        assertThat(trimmed)
                .extracting(ChatMessageDTO::getRole)
                .containsExactly("user");
        assertThat(trimmed.getFirst().getContent()).isEqualTo("final");
    }
}
