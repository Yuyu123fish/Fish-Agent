package com.yuyu.fishagent.llm.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FishLlmPropertiesBudgetTest {

    @Test
    void returnsModelOverrideBeforeGlobalContextWindow() {
        FishLlmProperties properties = new FishLlmProperties();
        properties.setContextWindowTokens(32_768);
        properties.setModelContextOverrides(Map.of("deepseek-chat", 65_536));

        assertThat(properties.getEffectiveContextWindowTokens("deepseek-chat")).isEqualTo(65_536);
        assertThat(properties.getEffectiveContextWindowTokens("unknown-model")).isEqualTo(32_768);
        assertThat(properties.getEffectiveContextWindowTokens(null)).isEqualTo(32_768);
    }
}
