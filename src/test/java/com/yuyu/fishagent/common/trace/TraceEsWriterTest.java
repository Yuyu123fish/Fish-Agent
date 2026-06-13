package com.yuyu.fishagent.common.trace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TraceEsWriterTest {

    @Test
    void enforcesDocumentMaxCharsBeforePersist() {
        TraceProperties properties = new TraceProperties();
        properties.setDocMaxChars(1_200);
        properties.setSnippetMaxChars(200);
        TraceCollector collector = new TraceCollector(properties);
        TurnTrace trace = collector.startTurn("turn-1", "sid", "trace-1");

        for (int i = 0; i < 50; i++) {
            collector.recordNode("turn-1", "node-" + i, "streaming", "X".repeat(200), 0, "SUCCESS");
        }

        @SuppressWarnings("unchecked")
        ObjectProvider<ElasticsearchOperations> operationsProvider = mock(ObjectProvider.class);
        TraceEsWriter writer = new TraceEsWriter(operationsProvider, properties);
        writer.enforceDocumentLimit(trace);

        assertThat(trace.getNodes().size()).isLessThan(50);
    }
}
