package com.yuyu.fishagent.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

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

    /**
     * 默认模型上下文窗口大小。未知模型使用该值作为预算基准。
     */
    private int contextWindowTokens = 32_768;

    /**
     * 预留给模型输出的 token 数，避免输入挤占回复空间。
     */
    private int outputReserveTokens = 2_048;

    /**
     * 输入预算安全区比例，用于吸收本地估算误差和模型 role token 开销。
     */
    private double safetyMarginRatio = 0.2;

    /**
     * 按模型名覆盖上下文窗口大小。key 应与当前激活模型配置值一致。
     */
    private Map<String, Integer> modelContextOverrides = new HashMap<>();

    /**
     * Resolve the context window for the current model.
     *
     * @param modelName active model name, may be blank
     * @return model-specific override or global default
     */
    public int getEffectiveContextWindowTokens(String modelName) {
        if (modelName != null && modelContextOverrides != null) {
            Integer override = modelContextOverrides.get(modelName);
            if (override != null && override > 0) {
                return override;
            }
        }
        return contextWindowTokens;
    }
}
