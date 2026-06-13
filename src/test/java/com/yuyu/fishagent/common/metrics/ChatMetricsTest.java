package com.yuyu.fishagent.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMetricsTest {

    @Test
    void shouldRegisterChatTurnTimerWithOutcomeTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatMetrics metrics = new ChatMetrics(registry);

        metrics.chatTurnTimer(ChatMetrics.Outcome.SUCCESS).record(() -> {
        });

        assertThat(registry.find("fish.chat.turn.duration")
                .tag("outcome", "success")
                .timer()).isNotNull();
    }

    @Test
    void shouldRegisterRagLegTimerWithLegTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatMetrics metrics = new ChatMetrics(registry);

        metrics.ragLegTimer(ChatMetrics.RagLeg.RERANK).record(() -> {
        });

        assertThat(registry.find("fish.rag.recall.duration")
                .tag("leg", "rerank")
                .timer()).isNotNull();
    }
}
