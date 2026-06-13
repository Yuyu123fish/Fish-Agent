package com.yuyu.fishagent.agent.tool;

import com.yuyu.fishagent.agent.config.ToolProperties;
import com.yuyu.fishagent.agent.tool.result.LargeResultScratchStore;
import com.yuyu.fishagent.agent.tool.result.ToolResultBudgeter;
import com.yuyu.fishagent.agent.tool.result.ToolResultGovernor;
import com.yuyu.fishagent.agent.tool.result.ToolResultProperties;
import com.yuyu.fishagent.agent.tool.result.ToolResultSummarizer;
import com.yuyu.fishagent.common.trace.TraceCollector;
import com.yuyu.fishagent.common.trace.TraceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryTest {

    @Test
    void governResultKeepsFinalOutputWithinConfiguredLimit() throws Exception {
        ToolProperties properties = new ToolProperties();
        properties.setMaxResultChars(120);
        properties.setHintThresholdChars(20);
        properties.setOverrides(java.util.Map.of("web_fetch", 120));
        ToolRegistry registry = new ToolRegistry(List.of(), properties);

        Method method = ToolRegistry.class.getDeclaredMethod("governResult", String.class, String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(registry, "web_fetch", "x".repeat(500));

        assertThat(result).hasSizeLessThanOrEqualTo(120);
    }

    @Test
    void turnBoundCallbackAppliesScratchGovernance() {
        ToolResultProperties resultProperties = new ToolResultProperties();
        resultProperties.setBudgetTokens(120);
        resultProperties.setScratchLargeThresholdTokens(150);
        resultProperties.setScratchChunkTokens(50);
        resultProperties.setSummarizeThresholdTokens(10_000);
        TraceCollector traceCollector = new TraceCollector(new TraceProperties());
        traceCollector.startTurn("turn-1", "sid", "trace-1");
        ToolResultGovernor governor = new ToolResultGovernor(
                resultProperties,
                new ToolResultBudgeter(),
                mock(ToolResultSummarizer.class),
                newScratchStore(resultProperties),
                traceCollector);
        AgentToolProvider provider = new AgentToolProvider() {
            @Override
            public String name() {
                return "huge_log";
            }

            @Override
            public ToolCallback build() {
                return new ToolCallback() {
                    @Override
                    public String call(String toolInput) {
                        return "error 500\n".repeat(500);
                    }

                    @Override
                    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                        return null;
                    }
                };
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(provider), new ToolProperties(), governor);
        registry.init();

        String result = registry.allCallbacks("turn-1").get(0).call("{}");

        assertThat(result).contains("search_large_result");
        assertThat(traceCollector.current("turn-1").getNodes())
                .anySatisfy(node -> assertThat(node.getDisposition()).isEqualTo("retrieved"));
    }

    @SuppressWarnings("unchecked")
    private LargeResultScratchStore newScratchStore(ToolResultProperties properties) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new LargeResultScratchStore(provider, new ObjectMapper(), properties);
    }
}
