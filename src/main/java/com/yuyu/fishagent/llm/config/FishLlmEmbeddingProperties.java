package com.yuyu.fishagent.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 非对话链路所用嵌入模型路由，绑定 {@code fish.llm.embedding.*}。
 * <p>与 {@link FishLlmProperties}（对话 {@code fish.llm.chat-provider}）分离，避免与
 * {@code spring.ai.model.chat} 强耦合，并便于后续增加其它非对话模型配置。</p>
 * <p>未在 YAML 中配置 {@code provider} 时，使用默认值 {@link FishLlmChatProvider#DASHSCOPE}。</p>
 * <p>{@link FishLlmChatProvider#DEEPSEEK} 仅用于对话；嵌入请勿配置为该值（启动时在 {@link FishEmbeddingModelConfiguration} 中会失败）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.llm.embedding")
public class FishLlmEmbeddingProperties {

    /**
     * 向量化（长期记忆写入、RAG kNN 等）使用的提供方；取值与对话枚举相同。
     * <p>对应配置键 {@code fish.llm.embedding.provider}，可用环境变量
     * {@code FISH_LLM_EMBEDDING_PROVIDER} 覆盖。</p>
     */
    private FishLlmChatProvider provider = FishLlmChatProvider.DASHSCOPE;
}
