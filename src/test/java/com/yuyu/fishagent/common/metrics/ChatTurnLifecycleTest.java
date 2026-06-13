package com.yuyu.fishagent.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatTurnLifecycleTest {

    @Test
    void shouldRecordErrorOnlyOnceWhenFinishedMultipleTimes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatTurnLifecycle lifecycle = ChatTurnLifecycle.start(new ChatMetrics(registry));

        lifecycle.error(new RuntimeException("timeout"));
        lifecycle.error(new RuntimeException("disconnect"));

        assertThat(registry.find("fish.chat.turn.duration")
                .tag("outcome", "error")
                .timer()
                .count()).isEqualTo(1);
    }
}
