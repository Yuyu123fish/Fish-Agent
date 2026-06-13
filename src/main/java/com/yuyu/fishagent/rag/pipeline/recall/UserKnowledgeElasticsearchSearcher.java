package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.rag.document.UserMemoryDocument;
import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.rag.config.KnowledgeProperties;
import com.yuyu.fishagent.rag.config.RagProperties;
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
 * 用户私有「文档知识库」索引检索：与 {@link UserMemoryElasticsearchSearcher}（对话事实 fish-user-memory）分离，
 * 在 {@link KnowledgeProperties#getUserKnowledgeIndexName()} 上按 {@code user_id} 强制隔离。
 * <p>复用 {@link UserMemoryDocument} 作为 POJO，通过 {@link IndexCoordinates} 指定索引名。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserKnowledgeElasticsearchSearcher implements RagRecall.DocumentSearcher {

    private final ObjectProvider<ElasticsearchOperations> operationsProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final KnowledgeProperties knowledgeProperties;
    private final RagProperties ragProperties;

    @Override
    public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
        String uid = currentUserIdString();
        if (uid == null) {
            return List.of();
        }
        ElasticsearchOperations operations = operationsProvider.getIfAvailable();
        if (operations == null || subQueryText == null || subQueryText.isBlank()) {
            return List.of();
        }
        IndexCoordinates index = IndexCoordinates.of(knowledgeProperties.getUserKnowledgeIndexName());
        NativeQuery query = NativeQuery.builder()
                .withMaxResults(size)
                .withQuery(q -> q.bool(b -> b
                        // 新文档优先命中 contextualized_content；保留 content 兼容历史切片。
                        .must(m -> m.bool(bb -> bb
                                .should(s -> s.match(mt -> mt.field("contextualized_content").query(subQueryText)))
                                .should(s -> s.match(mt -> mt.field("content").query(subQueryText)))
                                .minimumShouldMatch("1")))
                        .filter(f -> f.term(t -> t.field("user_id").value(uid)))))
                .build();
        return mapHits(operations.search(query, UserMemoryDocument.class, index), RagRecall.RecallSource.TEXT);
    }

    @Override
    public List<RagRecall.RecallHit> searchByVector(String sessionId, String textToEmbed, int size) {
        if (!ragProperties.getRecall().isVectorLegEnabled()) {
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
            log.warn("[UserKnowledgeSearch] 嵌入失败: {}", e.getMessage());
            return List.of();
        }
        int k = Math.max(1, size);
        int numCandidates = Math.max(k, ragProperties.getRecall().getKnnNumCandidates());
        IndexCoordinates index = IndexCoordinates.of(knowledgeProperties.getUserKnowledgeIndexName());

        NativeQuery q = NativeQuery.builder()
                .withMaxResults(k)
                .withKnnSearches(kn -> kn
                        .field("embedding")
                        .queryVector(queryVector)
                        .k(k)
                        .numCandidates(numCandidates)
                        .filter(f -> f.term(t -> t.field("user_id").value(uid))))
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
            out.add(new RagRecall.RecallHit(id, doc.getContent().trim(), h.getScore(), source,
                    SourceAuthority.labelForKnowledge(doc.getAuthority(), false),
                    doc.getAuthority(), doc.bestCreatedAt(), doc.getDocId(), doc.getChunkIndex()));
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
