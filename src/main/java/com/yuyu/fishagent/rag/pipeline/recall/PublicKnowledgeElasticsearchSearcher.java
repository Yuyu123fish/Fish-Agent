package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.rag.document.PublicKnowledgeDocument;
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
 * 公有知识库索引检索：对所有登录用户开放，不加 user_id 过滤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicKnowledgeElasticsearchSearcher implements RagRecall.DocumentSearcher {

    private final ObjectProvider<ElasticsearchOperations> operationsProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final KnowledgeProperties knowledgeProperties;
    private final RagProperties ragProperties;

    /**
     * 公有库全文检索。
     */
    @Override
    public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
        ElasticsearchOperations operations = operationsProvider.getIfAvailable();
        if (operations == null || subQueryText == null || subQueryText.isBlank()) {
            return List.of();
        }
        IndexCoordinates index = IndexCoordinates.of(knowledgeProperties.getPublicIndexName());
        NativeQuery query = NativeQuery.builder()
                .withMaxResults(size)
                .withQuery(q -> q.bool(b -> b
                        // 新文档优先命中 contextualized_content；保留 content 兼容历史切片。
                        .should(s -> s.match(m -> m.field("contextualized_content").query(subQueryText)))
                        .should(s -> s.match(m -> m.field("content").query(subQueryText)))
                        .minimumShouldMatch("1")
                        // 半批可见性：排除入库中(ready=false)的切片；存量切片无该字段，must_not 不命中故仍可见
                        .mustNot(m -> m.term(t -> t.field("ready").value(false)))))
                .build();
        return mapHits(operations.search(query, PublicKnowledgeDocument.class, index), RagRecall.RecallSource.TEXT);
    }

    /**
     * 公有库向量检索。
     */
    @Override
    public List<RagRecall.RecallHit> searchByVector(String sessionId, String textToEmbed, int size) {
        if (!ragProperties.getRecall().isVectorLegEnabled()) {
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
            log.warn("[PublicKnowledgeSearch] 嵌入失败: {}", e.getMessage());
            return List.of();
        }
        int k = Math.max(1, size);
        int numCandidates = Math.max(k, ragProperties.getRecall().getKnnNumCandidates());
        IndexCoordinates index = IndexCoordinates.of(knowledgeProperties.getPublicIndexName());

        NativeQuery q = NativeQuery.builder()
                .withMaxResults(k)
                .withKnnSearches(kn -> kn
                        .field("embedding")
                        .queryVector(queryVector)
                        .k(k)
                        .numCandidates(numCandidates)
                        // 半批可见性：kNN 预过滤同样排除入库中(ready=false)的切片
                        .filter(f -> f.bool(nb -> nb
                                .mustNot(mm -> mm.term(t -> t.field("ready").value(false))))))
                .build();
        return mapHits(operations.search(q, PublicKnowledgeDocument.class, index), RagRecall.RecallSource.VECTOR);
    }

    private static List<RagRecall.RecallHit> mapHits(SearchHits<PublicKnowledgeDocument> hits, RagRecall.RecallSource source) {
        List<RagRecall.RecallHit> out = new ArrayList<>();
        for (SearchHit<PublicKnowledgeDocument> h : hits.getSearchHits()) {
            PublicKnowledgeDocument doc = h.getContent();
            if (doc == null || doc.getContent() == null || doc.getContent().isBlank()) {
                continue;
            }
            String id = h.getId() != null ? h.getId() : doc.getId();
            out.add(new RagRecall.RecallHit(id, doc.getContent().trim(), h.getScore(), source,
                    SourceAuthority.labelForKnowledge(doc.getAuthority(), true),
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
