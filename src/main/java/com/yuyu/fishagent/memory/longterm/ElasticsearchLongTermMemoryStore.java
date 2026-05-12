package com.yuyu.fishagent.memory.longterm;

import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.rag.document.UserMemoryDocument;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于 Elasticsearch 的长期事实存储。
 * <p>长期记忆默认关闭；开启后也通过 {@link LongTermMemoryStore} 接口隔离 ES 细节。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElasticsearchLongTermMemoryStore implements LongTermMemoryStore {

    private final ObjectProvider<ElasticsearchOperations> operationsProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MemoryProperties properties;

    /**
     * 应用启动时按需创建长期记忆索引。
     * <p>只有显式开启长期记忆时才初始化 ES，避免本地未配置 ES 时影响聊天功能。</p>
     */
    @PostConstruct
    public void initIndex() {
        log.debug("[ElasticsearchLongTermMemoryStore] 初始化检查 longTermEnabled={}, indexName={}, dims={}",
                properties.isLongTermEnabled(), properties.getLongTermIndexName(), properties.getEmbeddingDimensions());
        // 只有显式开启长期记忆时才初始化索引
        if (!properties.isLongTermEnabled()) {
            log.debug("[ElasticsearchLongTermMemoryStore] 长期记忆未启用，跳过 ES 索引初始化");
            return;
        }
        // 优雅降级：ES 不可用时不影响应用启动
        ElasticsearchOperations operations = operationsProvider.getIfAvailable();
        if (operations == null) {
            log.debug("[ElasticsearchLongTermMemoryStore] ElasticsearchOperations 不可用，跳过长期记忆索引初始化");
            return;
        }

        try {
            IndexOperations indexOps = operations.indexOps(IndexCoordinates.of(properties.getLongTermIndexName()));
            if (!indexOps.exists()) {
                log.debug("[ElasticsearchLongTermMemoryStore] 长期记忆索引不存在，准备创建 indexName={}", properties.getLongTermIndexName());
                // 创建索引并配置向量映射
                indexOps.create();
                indexOps.putMapping(vectorMapping());
                log.info("[ElasticsearchLongTermMemoryStore] 已创建长期记忆向量索引: {}", properties.getLongTermIndexName());
            } else {
                log.debug("[ElasticsearchLongTermMemoryStore] 长期记忆索引已存在 indexName={}", properties.getLongTermIndexName());
            }
        } catch (Exception e) {
            log.warn("[ElasticsearchLongTermMemoryStore] 初始化长期记忆索引失败: {}", e.getMessage());
        }
    }

    /**
     * 将长期事实写入用户私有 ES 向量索引（带 {@code user_id}）。
     * <p>每条事实先通过 {@link EmbeddingModel} 转成向量，再与原文一起保存。</p>
     *
     * @param userId    归属用户；异步线程中调用方必须显式传入（勿依赖 ThreadLocal）
     * @param sessionId 来源会话 ID（便于日志追溯）
     * @param facts     模型提取出的长期事实列表
     */
    @Override
    public void saveFacts(Long userId, String sessionId, List<String> facts) {
        log.debug("[ElasticsearchLongTermMemoryStore] 准备写入长期事实 uid={}, sid={}, enabled={}, factsCount={}",
                userId, sessionId, properties.isLongTermEnabled(), facts == null ? 0 : facts.size());
        if (!properties.isLongTermEnabled()) {
            log.debug("[ElasticsearchLongTermMemoryStore] 长期记忆未启用，跳过长期事实写入 sid={}", sessionId);
            return;
        }
        if (userId == null) {
            log.debug("[ElasticsearchLongTermMemoryStore] userId 为空，跳过长期事实写入 sid={}", sessionId);
            return;
        }
        if (facts == null || facts.isEmpty()) {
            log.debug("[ElasticsearchLongTermMemoryStore] facts 为空，跳过长期事实写入 sid={}", sessionId);
            return;
        }

        // 优雅降级：依赖不可用时跳过写入
        ElasticsearchOperations operations = operationsProvider.getIfAvailable();
        if (operations == null) {
            log.debug("[ElasticsearchLongTermMemoryStore] ElasticsearchOperations 不可用，跳过长期记忆写入");
            return;
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            log.warn("[ElasticsearchLongTermMemoryStore] EmbeddingModel 不可用，跳过向量长期记忆写入");
            return;
        }

        IndexCoordinates index = IndexCoordinates.of(properties.getLongTermIndexName());
        long now = System.currentTimeMillis();
        // 遍历每条事实，分别进行向量化并写入 ES
        for (String fact : facts) {
            if (fact == null || fact.isBlank()) {
                log.debug("[ElasticsearchLongTermMemoryStore] 跳过空长期事实 sid={}", sessionId);
                continue;
            }
            try {
                // 使用 EmbeddingModel 将事实文本转换为向量
                List<Float> embedding = toFloatList(embeddingModel.embed(fact.trim()));
                String id = UUID.randomUUID().toString();
                UserMemoryDocument document = new UserMemoryDocument();
                document.setId(id);
                document.setUserId(String.valueOf(userId));
                document.setContent(fact.trim());
                document.setCreatedAt(now);
                document.setEmbedding(embedding);
                document.setSourceType("chat");
                operations.save(document, index);
                log.debug("[ElasticsearchLongTermMemoryStore] 长期事实写入完成 sid={}, id={}, index={}, factLen={}, dims={}",
                        sessionId, id, properties.getLongTermIndexName(), fact.trim().length(), embedding.size());
            } catch (Exception e) {
                log.warn("[ElasticsearchLongTermMemoryStore] 写入长期记忆失败 sid={}: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 构建 ES mapping，确保 embedding 字段是可检索的 dense_vector。
     * 使用 cosine 相似度度量，适用于语义检索场景
     */
    private Document vectorMapping() {
        Document mapping = Document.create();
        mapping.put("properties", Map.of(
                "user_id", Map.of("type", "keyword"),
                "content", Map.of("type", "text"),
                "created_at", Map.of("type", "date", "format", "epoch_millis"),
                "embedding", Map.of(
                        "type", "dense_vector",
                        "dims", properties.getEmbeddingDimensions(),
                        "index", true,
                        "similarity", "cosine"
                )
        ));
        return mapping;
    }

    /**
     * Spring AI 返回 primitive float[]，ES 文档序列化使用 List 更稳定。
     */
    private List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }
}
