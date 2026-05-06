package com.yuyu.fishagent.service;

import com.yuyu.fishagent.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
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
}
