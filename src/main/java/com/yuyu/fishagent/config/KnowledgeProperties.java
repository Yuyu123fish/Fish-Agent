package com.yuyu.fishagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库索引与入队配置：{@code fish.knowledge.*}。
 * <p>{@code scope_type=PRIVATE} 的文档切片写入 {@link #userKnowledgeIndexName}（用户个人知识库）；
 * {@code PUBLIC} 写入 {@link #publicIndexName}（组织/公共知识库）。与 {@code fish-user-memory}（对话事实，Java 写入）分离。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.knowledge")
public class KnowledgeProperties {

    /**
     * 孤儿 PROCESSING 任务补偿（Java 定时任务）：{@code fish.knowledge.compensation.*}。
     * <p>用于 Worker 崩溃后清理 ES 残留并将 MySQL 标记失败。</p>
     */
    @Data
    public static class CompensationProperties {
        /** 是否启用补偿调度；false 时不注册补偿 Bean（见 {@code @ConditionalOnProperty}）。 */
        private boolean enabled = true;
        /** 超过该分钟数仍停留在 PROCESSING 则视为孤儿。 */
        private int timeoutMinutes = 10;
    }

    /** Elasticsearch 公有知识库索引名。 */
    private String publicIndexName = "fish-public-knowledge";

    /**
     * 用户上传文档切片索引（与对话长期事实索引 fish-user-memory 分离）。
     * 环境变量 {@code KNOWLEDGE_USER_INDEX} 与 Python Worker 对齐。
     */
    private String userKnowledgeIndexName = "fish-user-knowledge";

    /**
     * 文档解析任务投递的 Redis Stream 键名（与 Python Worker 约定一致）。
     */
    private String documentIngestStreamKey = "fish:doc:ingest";

    /** 孤儿任务补偿参数。 */
    private CompensationProperties compensation = new CompensationProperties();
}
