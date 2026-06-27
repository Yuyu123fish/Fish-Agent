package com.yuyu.fishagent.llm.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 收口「活跃模型名 + 有效上下文窗口」的解析，供 ContextBudgetAllocator 与
 * ToolResultGovernor 共用同一真相源（避免 V3→V4 迁移时 override 漏改一类问题）。
 */
class ActiveChatModelContextTest {

    @Test
    void effectiveWindowUsesOverrideForActiveDeepSeekModel() {
        FishLlmProperties props = new FishLlmProperties();
        props.setChatProvider(FishLlmChatProvider.DEEPSEEK);
        props.setModelContextOverrides(Map.of("deepseek-v4-flash", 1_048_576));

        Environment env = mock(Environment.class);
        when(env.getProperty("spring.ai.openai.chat.options.model")).thenReturn("deepseek-v4-flash");

        ActiveChatModelContext ctx = new ActiveChatModelContext(props, env);

        assertThat(ctx.activeModelName()).isEqualTo("deepseek-v4-flash");
        assertThat(ctx.effectiveContextWindow()).isEqualTo(1_048_576);
    }

    @Test
    void effectiveWindowFallsBackToDefaultWhenModelNotInOverrides() {
        FishLlmProperties props = new FishLlmProperties();
        props.setChatProvider(FishLlmChatProvider.DEEPSEEK);
        props.setContextWindowTokens(32_768);

        Environment env = mock(Environment.class);
        when(env.getProperty("spring.ai.openai.chat.options.model")).thenReturn("deepseek-unknown");

        ActiveChatModelContext ctx = new ActiveChatModelContext(props, env);

        assertThat(ctx.effectiveContextWindow()).isEqualTo(32_768);
    }

    @Test
    void resolvesOllamaAndDashScopeModelNamesFromProviderSpecificProperties() {
        Environment env = mock(Environment.class);
        when(env.getProperty("spring.ai.ollama.chat.options.model")).thenReturn("qwen2.5:7b");
        when(env.getProperty("spring.ai.dashscope.chat.options.model")).thenReturn("qwen-plus");

        FishLlmProperties ollamaProps = new FishLlmProperties();
        ollamaProps.setChatProvider(FishLlmChatProvider.OLLAMA);
        assertThat(new ActiveChatModelContext(ollamaProps, env).activeModelName()).isEqualTo("qwen2.5:7b");

        FishLlmProperties dashProps = new FishLlmProperties();
        dashProps.setChatProvider(FishLlmChatProvider.DASHSCOPE);
        assertThat(new ActiveChatModelContext(dashProps, env).activeModelName()).isEqualTo("qwen-plus");
    }
}
