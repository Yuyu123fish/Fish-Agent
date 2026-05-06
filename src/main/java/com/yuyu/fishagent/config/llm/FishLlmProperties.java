package com.yuyu.fishagent.config.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Fish-Agent<strong>对话</strong>大模型路由（{@code fish.llm.chat-provider}）。
 * <p>
 * 嵌入等非对话模型见 {@link FishLlmEmbeddingProperties}（{@code fish.llm.embedding.*}），与此类解耦。
 * </p>
 * <p>
 * 与框架属性 {@code spring.ai.model.chat} 的同步规则见 {@link FishLlmEnvironmentPostProcessor}
 * 与启动时的 {@link FishLlmConfigurationConsistencyLogger}。
 * </p>
 *
 * <p><strong>注意：</strong>本地 7B 在工具调用、严格 JSON（短期摘要 / 长期事实解析）上可能弱于云端，
 * 属模型能力差异，非集成缺陷。若需记忆链路单独用小模型，可后续再拆专用 {@code ChatModel} Bean。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.llm")
public class FishLlmProperties {

    /**
     * 对话使用的模型提供方；与 {@code spring.ai.model.chat} 应对齐。
     * <p>默认走 DashScope，与未引入 Ollama 前的行为一致。</p>
     */
    private FishLlmChatProvider chatProvider = FishLlmChatProvider.DASHSCOPE;
}
