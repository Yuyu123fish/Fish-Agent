package com.yuyu.fishagent.config.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动完成后记录 {@code fish.llm.embedding.provider}，并对比 {@code fish.llm.chat-provider}
 * 与已解析的 {@code spring.ai.model.chat}，在两者语义不一致时打印告警。
 */
@Slf4j
@Component
@Order(1000)
@RequiredArgsConstructor
public class FishLlmConfigurationConsistencyLogger implements ApplicationRunner {

    private final Environment environment;
    private final FishLlmProperties fishLlmProperties;
    private final FishLlmEmbeddingProperties fishLlmEmbeddingProperties;

    /**
     * 校验并记录当前 LLM 路由配置是否自洽。
     *
     * @param args 应用启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("[FishLlm] 嵌入模型路由：fish.llm.embedding.provider={}（与对话独立；未配置时默认 DASHSCOPE）",
                fishLlmEmbeddingProperties.getProvider());

        String resolved = environment.getProperty("spring.ai.model.chat");
        String expected = fishLlmProperties.getChatProvider().toSpringAiModelChatValue();
        if (resolved == null) {
            log.warn("[FishLlm] spring.ai.model.chat 未解析到值，请检查 Spring AI 版本与自动配置；fish.llm.chat-provider={}",
                    fishLlmProperties.getChatProvider());
            return;
        }
        if (!resolved.equalsIgnoreCase(expected)) {
            log.warn(
                    "[FishLlm] 配置可能不一致：fish.llm.chat-provider={} 对应 spring.ai.model.chat 应为 '{}'，"
                            + "但实际解析为 '{}'。若以框架属性为准，请同步修改 fish.llm.chat-provider 或删除显式的 spring.ai.model.chat。",
                    fishLlmProperties.getChatProvider(), expected, resolved);
        } else {
            log.info("[FishLlm] 对话模型路由：fish.llm.chat-provider={}，spring.ai.model.chat={}",
                    fishLlmProperties.getChatProvider(), resolved);
        }
        // 当使用 DeepSeek 时打印实际模型名，便于对照 API 用量面板
        if (fishLlmProperties.getChatProvider() == FishLlmChatProvider.DEEPSEEK) {
            String model = environment.getProperty("spring.ai.openai.chat.options.model", "(未配置)");
            log.info("[FishLlm] DeepSeek 模型：spring.ai.openai.chat.options.model={}", model);
        }
    }
}
