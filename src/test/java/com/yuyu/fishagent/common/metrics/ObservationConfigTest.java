package com.yuyu.fishagent.common.metrics;

import com.yuyu.fishagent.common.trace.TraceCollector;
import com.yuyu.fishagent.common.trace.TraceContext;
import com.yuyu.fishagent.common.trace.TraceObservationHandler;
import com.yuyu.fishagent.common.trace.TraceProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationConfigTest {

    @Test
    void registersTraceObservationHandlerIntoRegistry() {
        TraceCollector collector = new TraceCollector(new TraceProperties());
        collector.startTurn("turn-1", "sid", "trace-1");
        ObservationRegistry registry = ObservationRegistry.create();

        new ObservationConfig()
                .traceObservationRegistryCustomizer(new TraceObservationHandler(collector))
                .customize(registry);

        TraceContext.setTurnId("turn-1");
        try {
            Observation.createNotStarted("gen_ai.chat", registry).observe(() -> {
                // no-op: the test verifies that onStop reaches TraceObservationHandler.
            });
        } finally {
            TraceContext.clear();
        }

        assertThat(collector.current("turn-1").getNodes())
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.getNodeName()).isEqualTo("gen_ai.chat");
                    assertThat(node.getType()).isEqualTo("llm");
                });
    }
}
