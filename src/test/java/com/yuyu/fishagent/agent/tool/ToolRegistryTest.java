package com.yuyu.fishagent.agent.tool;

import com.yuyu.fishagent.agent.config.ToolProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
