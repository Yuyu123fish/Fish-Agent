package com.yuyu.fishagent.memory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 记忆压缩配置，对应 {@code fish.memory.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.memory")
public class MemoryProperties {

    /**
     * 记忆压缩 / 长期事实抽取链路使用的对话模型子配置（{@code fish.memory.chat.*}）。
     * <p>可与主对话模型分离，减轻主模型负载或选用更经济的模型。</p>
     */
    @Data
    public static class MemoryChatProperties {
        /**
         * 是否启用独立记忆模型；false 时记忆链路降级为与主对话相同的 {@code @Primary} ChatModel。
         */
        private boolean enabled = true;
        /**
         * 独立记忆模型名称（OpenAI 兼容 / DeepSeek 路径下有效）；默认与常见主模型一致。
         */
        private String model = "deepseek-chat";
        /**
         * 记忆链路调用温度，摘要与事实抽取宜偏低以保证稳定。
         */
        private double temperature = 0.1;
    }

    /**
     * 短期记忆保留的最近消息数量。
     */
    private int shortTermWindowSize = 20;

    /**
     * 触发压缩的建议消息阈值，供上层编排使用。
     */
    private int summaryTriggerThreshold = 30;

    /**
     * Redis key 前缀。
     */
    private String redisKeyPrefix = "fish:memory";

    /**
     * 短期记忆 Redis TTL 天数。
     */
    private long shortTermTtlDays = 30;

    /**
     * 是否启用长期事实写入 Elasticsearch。
     */
    private boolean longTermEnabled = true;

    /**
     * 长期事实写入的 Elasticsearch 索引名。
     */
    private String longTermIndexName = "fish-user-memory";

    /**
     * 长期记忆向量维度，需要与实际 Embedding 模型输出一致。
     */
    private int embeddingDimensions = 1536;

    /**
     * {@code fish.memory.chat}：记忆链路专用 Chat 模型参数。
     */
    private MemoryChatProperties chat = new MemoryChatProperties();
}
