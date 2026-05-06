package com.yuyu.fishagent.agent.memory.rag.recall;

import com.yuyu.fishagent.agent.memory.longterm.UserMemoryDocument;
import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.config.MemoryProperties;
import com.yuyu.fishagent.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户私有记忆索引检索：文本与向量查询均强制 {@code user_id} term 过滤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserMemoryElasticsearchSearcher implements RagRecall.DocumentSearcher {

    private final ObjectProvider<ElasticsearchOperations> operationsProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MemoryProperties memoryProperties;
    private final RagProperties ragProperties;

    /**
     * 全文检索：match(content) + filter(user_id)。
     */
    @Override
    public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
        if (!memoryProperties.isLongTermEnabled()) {
            return List.of();
        }
        String uid = currentUserIdString();
        if (uid == null) {
            return List.of();
        }
        ElasticsearchOperations operations = operationsProvider.getIfAvailable();
        if (operations == null || subQueryText == null || subQueryText.isBlank()) {
            return List.of();
        }
        IndexCoordinates index = IndexCoordinates.of(memoryProperties.getLongTermIndexName());
        // 防御性约束：仅召回对话摘要事实（source_type=chat），避免历史误写入的文档切片混入记忆上下文
        NativeQuery query = NativeQuery.builder()
                .withMaxResults(size)
                .withQuery(q -> q.bool(b -> b
                        .must(m -> m.match(mt -> mt.field("content").query(subQueryText)))
                        .filter(f -> f.term(t -> t.field("source_type").value("chat")))
                        .filter(f -> f.term(t -> t.field("user_id").value(uid)))))
                .build();
        return mapHits(operations.search(query, UserMemoryDocument.class, index), RagRecall.RecallSource.TEXT);
    }

    /**
     * kNN 检索：带 {@code user_id} filter，避免跨用户向量召回。
     */
    @Override
    public List<RagRecall.RecallHit> searchByVector(String sessionId, String textToEmbed, int size) {
        if (!memoryProperties.isLongTermEnabled() || !ragProperties.getRecall().isVectorLegEnabled()) {
            return List.of();
        }
        String uid = currentUserIdString();
        if (uid == null) {
            return List.of();
        }
        ElasticsearchOperations operations = operationsProvider.getIfAvailable();
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (operations == null || embeddingModel == null || textToEmbed == null || textToEmbed.isBlank()) {
            return List.of();
        }
        List<Float> queryVector;
        try {
            queryVector = toFloatList(embeddingModel.embed(textToEmbed.trim()));
        } catch (Exception e) {
            log.warn("[UserMemorySearch] 嵌入失败: {}", e.getMessage());
            return List.of();
        }
        int k = Math.max(1, size);
        int numCandidates = Math.max(k, ragProperties.getRecall().getKnnNumCandidates());
        IndexCoordinates index = IndexCoordinates.of(memoryProperties.getLongTermIndexName());

        NativeQuery q = NativeQuery.builder()
                .withMaxResults(k)
                .withKnnSearches(kn -> kn
                        .field("embedding")
                        .queryVector(queryVector)
                        .k(k)
                        .numCandidates(numCandidates)
                        .filter(f -> f.bool(b -> b
                                .filter(ff -> ff.term(t -> t.field("user_id").value(uid)))
                                .filter(ff -> ff.term(t -> t.field("source_type").value("chat"))))))
                .build();
        return mapHits(operations.search(q, UserMemoryDocument.class, index), RagRecall.RecallSource.VECTOR);
    }

    private static String currentUserIdString() {
        Long id = UserContextHolder.currentUserIdOrNull();
        return id == null ? null : String.valueOf(id);
    }

    private static List<RagRecall.RecallHit> mapHits(SearchHits<UserMemoryDocument> hits, RagRecall.RecallSource source) {
        List<RagRecall.RecallHit> out = new ArrayList<>();
        for (SearchHit<UserMemoryDocument> h : hits.getSearchHits()) {
            UserMemoryDocument doc = h.getContent();
            if (doc == null || doc.getContent() == null || doc.getContent().isBlank()) {
                continue;
            }
            String id = h.getId() != null ? h.getId() : doc.getId();
            out.add(new RagRecall.RecallHit(id, doc.getContent().trim(), h.getScore(), source));
        }
        return out;
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }
}
