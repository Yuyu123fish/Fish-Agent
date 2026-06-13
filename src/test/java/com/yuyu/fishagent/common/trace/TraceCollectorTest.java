package com.yuyu.fishagent.common.trace;

import com.yuyu.fishagent.common.metrics.ChatMetrics;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TraceCollectorTest {

    @Test
    void recordsSnippetsAndCompletesOnlyOnce() {
        TraceCollector collector = new TraceCollector(new TraceProperties());
        TurnTrace trace = collector.startTurn("turn-1", "sid", "trace-1");

        collector.recordRagInjected("turn-1", "R".repeat(500));
        collector.recordMemoryInjected("turn-1", "M".repeat(500));
        collector.recordNode("turn-1", "llm", "thought", "A".repeat(500), 12, "SUCCESS");
        collector.finishTurn("turn-1", ChatMetrics.Outcome.SUCCESS);
        collector.finishTurn("turn-1", ChatMetrics.Outcome.ERROR);

        assertThat(trace.getRagInjected()).hasSize(200);
        assertThat(trace.getMemoryInjected()).hasSize(200);
        assertThat(trace.getNodes()).hasSize(1);
        assertThat(trace.getNodes().get(0).getContentSnippet()).hasSize(200);
        assertThat(trace.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void recordsNodesSafelyFromConcurrentCallbacks() throws Exception {
        TraceCollector collector = new TraceCollector(new TraceProperties());
        TurnTrace trace = collector.startTurn("turn-1", "sid", "trace-1");
        ExecutorService pool = Executors.newFixedThreadPool(8);

        for (int i = 0; i < 100; i++) {
            int index = i;
            pool.submit(() -> collector.recordNode("turn-1", "node-" + index, "node", "content", 0, "SUCCESS"));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(trace.getNodes()).hasSize(100);
        assertThat(trace.getNodes())
                .extracting(TurnTrace.Node::getOrder)
                .doesNotHaveDuplicates()
                .contains(1, 100);
    }
}
