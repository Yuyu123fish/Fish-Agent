package com.yuyu.fishagent.memory.config;

import com.yuyu.fishagent.common.redis.RedisKeys;
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
        private String model = "deepseek-v4-flash";
        /**
         * 记忆链路调用温度，摘要与事实抽取宜偏低以保证稳定。
         */
        private double temperature = 0.1;
    }

    /**
     * 短期记忆对象存储快照子配置（{@code fish.memory.snapshot.*}）。
     * <p>作为 Redis 失效（TTL 到期 / 不可用）时的兜底兜源。</p>
     */
    @Data
    public static class SnapshotProperties {
        /** 是否启用 L2 对象存储快照兜底。false 时仅用 Redis，Redis 失效则回退全量历史窗口、摘要丢失。 */
        private boolean enabled = true;
        /** 冷会话（L1+L2 均未命中）且历史达到压缩阈值时，是否同步重算摘要（会阻塞首字 1-5s）。 */
        private boolean recomputeOnCold = true;
    }

    /**
     * 长期事实写入查重子配置（{@code fish.memory.dedup.*}）。
     */
    @Data
    public static class Dedup {
        /** 是否启用写入前 embedding 余弦查重；false 时行为与历史一致。 */
        private boolean enabled = true;
        /** 余弦相似度阈值，最近邻 >= 该值判定为重复并跳过写入。 */
        private double similarityThreshold = 0.92;
        /** knn 取回的最近邻条数。 */
        private int k = 3;
        /** knn 的 num_candidates。 */
        private int numCandidates = 20;
    }

    /**
     * 长期记忆冲突治理子配置（{@code fish.memory.conflict.*}）。
     */
    @Data
    public static class Conflict {
        /** 是否启用 LLM 判定 SAME/CONFLICT/NEITHER；关闭时保留历史“相似即跳过”行为。 */
        private boolean enabled = true;
        /** 与候选事实比较的相似旧事实数量。 */
        private int similarFactK = 5;
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
    private String redisKeyPrefix = RedisKeys.MEMORY;

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

    /**
     * {@code fish.memory.snapshot}：短期记忆对象存储快照兜底参数。
     */
    private SnapshotProperties snapshot = new SnapshotProperties();

    /**
     * {@code fish.memory.dedup}：长期事实写入前的 embedding 余弦查重参数。
     */
    private Dedup dedup = new Dedup();

    /**
     * {@code fish.memory.conflict}：相似长期事实的冲突与时效治理参数。
     */
    private Conflict conflict = new Conflict();
}
