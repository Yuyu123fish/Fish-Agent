package com.yuyu.fishagent.llm.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FishLlmChatProviderTest {

    @Test
    void parse_blank_defaultsToDashScope() {
        assertThat(FishLlmChatProvider.parse(null)).isEqualTo(FishLlmChatProvider.DASHSCOPE);
        assertThat(FishLlmChatProvider.parse("  ")).isEqualTo(FishLlmChatProvider.DASHSCOPE);
    }

    @Test
    void parse_caseInsensitive() {
        assertThat(FishLlmChatProvider.parse("ollama")).isEqualTo(FishLlmChatProvider.OLLAMA);
        assertThat(FishLlmChatProvider.parse("DASHSCOPE")).isEqualTo(FishLlmChatProvider.DASHSCOPE);
    }

    @Test
    void toSpringAiModelChatValue_matchesAutoconfigure() {
        assertThat(FishLlmChatProvider.DASHSCOPE.toSpringAiModelChatValue()).isEqualTo("dashscope");
        assertThat(FishLlmChatProvider.OLLAMA.toSpringAiModelChatValue()).isEqualTo("ollama");
        assertThat(FishLlmChatProvider.DEEPSEEK.toSpringAiModelChatValue()).isEqualTo("openai");
    }

    @Test
    void parse_deepseek_caseInsensitive() {
        assertThat(FishLlmChatProvider.parse("deepseek")).isEqualTo(FishLlmChatProvider.DEEPSEEK);
        assertThat(FishLlmChatProvider.parse("DEEPSEEK")).isEqualTo(FishLlmChatProvider.DEEPSEEK);
    }

    @Test
    void parse_unknown_throws() {
        assertThatThrownBy(() -> FishLlmChatProvider.parse("openai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openai");
    }
}
