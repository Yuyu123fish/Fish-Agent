package com.yuyu.fishagent.agent.tool.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.common.trace.TraceCollector;
import com.yuyu.fishagent.common.trace.TraceProperties;
import com.yuyu.fishagent.common.util.TokenEstimator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolResultGovernorTest {

    @Test
    void summarizesWhenOverSummaryThreshold() {
        ToolResultProperties properties = new ToolResultProperties();
        properties.setBudgetTokens(80);
        properties.setSummarizeThresholdTokens(100);
        properties.setScratchEnabled(false);
        TraceCollector traceCollector = new TraceCollector(new TraceProperties());
        traceCollector.startTurn("turn-1", "sid", "trace-1");
        ToolResultSummarizer summarizer = mock(ToolResultSummarizer.class);
        when(summarizer.summarize(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn("summary result");

        ToolResultGovernor governor = new ToolResultGovernor(
                properties,
                new ToolResultBudgeter(),
                summarizer,
                newStore(properties),
                traceCollector);

        ToolResultGovernor.GovernedResult result = governor.govern(
                "turn-1", "file_read", "{}", "long line ".repeat(500));

        assertThat(result.disposition()).isEqualTo("summarized");
        assertThat(result.content()).contains("summary result");
        assertThat(TokenEstimator.estimate(result.content())).isLessThanOrEqualTo(80);
        assertThat(traceCollector.current("turn-1").getNodes())
                .anySatisfy(node -> assertThat(node.getDisposition()).isEqualTo("summarized"));
    }

    @Test
    void storesHugeResultIntoScratchAndInjectsPreview() {
        ToolResultProperties properties = new ToolResultProperties();
        properties.setBudgetTokens(120);
        properties.setScratchLargeThresholdTokens(150);
        properties.setScratchChunkTokens(50);
        properties.setSummarizeThresholdTokens(10_000);
        TraceCollector traceCollector = new TraceCollector(new TraceProperties());
        traceCollector.startTurn("turn-1", "sid", "trace-1");

        ToolResultGovernor governor = new ToolResultGovernor(
                properties,
                new ToolResultBudgeter(),
                mock(ToolResultSummarizer.class),
                newStore(properties),
                traceCollector);

        ToolResultGovernor.GovernedResult result = governor.govern(
                "turn-1", "log_query", "{}", "error 500\n".repeat(500));

        assertThat(result.disposition()).isEqualTo("retrieved");
        assertThat(result.content()).contains("search_large_result");
        assertThat(TokenEstimator.estimate(result.content())).isLessThanOrEqualTo(120);
        assertThat(traceCollector.current("turn-1").getNodes())
                .anySatisfy(node -> assertThat(node.getDisposition()).isEqualTo("retrieved"));
    }

    @Test
    void storesHugeChineseResultWithoutExceedingPerResultBudget() {
        ToolResultProperties properties = new ToolResultProperties();
        properties.setBudgetTokens(300);
        properties.setScratchLargeThresholdTokens(400);
        properties.setScratchChunkTokens(120);
        properties.setScratchInjectTopK(3);
        properties.setSummarizeThresholdTokens(10_000);
        TraceCollector traceCollector = new TraceCollector(new TraceProperties());
        traceCollector.startTurn("turn-1", "sid", "trace-1");

        ToolResultGovernor governor = new ToolResultGovernor(
                properties,
                new ToolResultBudgeter(),
                mock(ToolResultSummarizer.class),
                newStore(properties),
                traceCollector);

        ToolResultGovernor.GovernedResult result = governor.govern(
                "turn-1", "log_query", "{}", "中文错误日志".repeat(1_000));

        assertThat(result.disposition()).isEqualTo("retrieved");
        assertThat(TokenEstimator.estimate(result.content())).isLessThanOrEqualTo(300);
        assertThat(traceCollector.current("turn-1").getNodes())
                .anySatisfy(node -> assertThat(node.getDisposition()).isEqualTo("retrieved"));
    }

    @SuppressWarnings("unchecked")
    private LargeResultScratchStore newStore(ToolResultProperties properties) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new LargeResultScratchStore(provider, new ObjectMapper(), properties);
    }
}
