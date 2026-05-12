package com.yuyu.fishagent.llm.config;

import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * 在同时引入 DashScope 与 Ollama starter 时，二者都会注册 {@link EmbeddingModel}，
 * 按类型注入或 {@link ObjectProvider#getIfAvailable()} 会因「候选 Bean 不唯一」失败。
 * <p>此处按 {@link FishLlmEmbeddingProperties#getProvider()} 选出主嵌入实现并标为 {@code @Primary}，
 * 供长期记忆写入、RAG 向量腿等统一注入；与对话路由 {@link FishLlmProperties#getChatProvider()} 独立。</p>
 * <p>长期记忆索引中的向量维度须与所选 Embedding 一致；切换提供方后需自行处理重建索引等问题。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class FishEmbeddingModelConfiguration {

    private final FishLlmEmbeddingProperties embeddingProperties;

    private final Environment environment;

    @Bean
    @Primary
    public EmbeddingModel fishPrimaryEmbeddingModel(
            ObjectProvider<OllamaEmbeddingModel> ollamaEmbeddingModel,
            ObjectProvider<DashScopeEmbeddingModel> dashScopeEmbeddingModel) {
        return switch (embeddingProperties.getProvider()) {
            case OLLAMA -> requireBean(
                    ollamaEmbeddingModel.getIfAvailable(),
                    "OLLAMA",
                    "OllamaEmbeddingModel");
            case DASHSCOPE -> requireBean(
                    dashScopeEmbeddingModel.getIfAvailable(),
                    "DASHSCOPE",
                    "DashScopeEmbeddingModel");
            case DEEPSEEK -> throw new IllegalStateException(
                    "fish.llm.embedding.provider=DEEPSEEK 暂不支持；嵌入请继续使用 DASHSCOPE 或 OLLAMA");
        };
    }

    private EmbeddingModel requireBean(
            EmbeddingModel impl,
            String providerLabel,
            String beanSimpleName) {
        if (impl != null) {
            return impl;
        }
        String chat = environment.getProperty("spring.ai.model.chat", "(未解析)");
        String msg = String.format(
                "fish.llm.embedding.provider=%s 需要已注册的 %s Bean，但未找到。"
                        + " 请确认对应 starter 与本地/云端依赖可用（当前 spring.ai.model.chat=%s，仅作排障参考）。",
                providerLabel, beanSimpleName, chat);
        log.error("[FishLlm] {}", msg);
        throw new IllegalStateException(msg);
    }
}
