package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.card.document.KnowledgeCardDocument;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
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
 * 用户私有「知识卡片」召回器。
 *
 * <p>卡片索引与文档知识库、对话记忆相互独立；这里只负责把 confirmed 卡片检索成统一的 RAG 命中，
 * 后续去重、融合、精排仍交给 {@link RagRecall.DefaultAugmentation} 处理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserKnowledgeCardSearcher implements RagRecall.DocumentSearcher {

    private static final String INDEX_NAME = "fish-knowledge-card";

    private final ObjectProvider<ElasticsearchOperations> operationsProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final RagProperties ragProperties;

    @Override
    public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
        Long userId = currentUserId();
        ElasticsearchOperations operations = operationsProvider.getIfAvailable();
        if (userId == null || operations == null || subQueryText == null || subQueryText.isBlank()) {
            return List.of();
        }

        NativeQuery query = NativeQuery.builder()
                .withMaxResults(Math.max(1, size))
                .withQuery(q -> q.bool(b -> b
                        // 标题、正文、关键词任一命中即可进入候选池，避免卡片标题过短时漏召回。
                        .must(m -> m.bool(bb -> bb
                                .should(s -> s.match(mt -> mt.field("title").query(subQueryText)))
                                .should(s -> s.match(mt -> mt.field("content").query(subQueryText)))
                                .should(s -> s.match(mt -> mt.field("keywords").query(subQueryText)))
                                .minimumShouldMatch("1")))
                        .filter(f -> f.term(t -> t.field("userId").value(userId)))
                        .filter(f -> f.term(t -> t.field("status").value(KnowledgeCard.STATUS_CONFIRMED)))))
                .build();
        return mapHits(operations.search(query, KnowledgeCardDocument.class, IndexCoordinates.of(INDEX_NAME)),
                RagRecall.RecallSource.TEXT);
    }

    @Override
    public List<RagRecall.RecallHit> searchByVector(String sessionId, String textToEmbed, int size) {
        if (!ragProperties.getRecall().isVectorLegEnabled()) {
            return List.of();
        }
        Long userId = currentUserId();
        ElasticsearchOperations operations = operationsProvider.getIfAvailable();
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (userId == null || operations == null || embeddingModel == null || textToEmbed == null || textToEmbed.isBlank()) {
            return List.of();
        }

        List<Float> queryVector;
        try {
            queryVector = toFloatList(embeddingModel.embed(textToEmbed.trim()));
        } catch (Exception e) {
            log.warn("[UserKnowledgeCardSearch] 嵌入失败: {}", e.getMessage());
            return List.of();
        }

        int k = Math.max(1, size);
        int numCandidates = Math.max(k, ragProperties.getRecall().getKnnNumCandidates());
        NativeQuery query = NativeQuery.builder()
                .withMaxResults(k)
                .withKnnSearches(kn -> kn
                        .field("embedding")
                        .queryVector(queryVector)
                        .k(k)
                        .numCandidates(numCandidates)
                        .filter(f -> f.bool(b -> b
                                .must(m -> m.term(t -> t.field("userId").value(userId)))
                                .must(m -> m.term(t -> t.field("status").value(KnowledgeCard.STATUS_CONFIRMED))))))
                .build();
        return mapHits(operations.search(query, KnowledgeCardDocument.class, IndexCoordinates.of(INDEX_NAME)),
                RagRecall.RecallSource.VECTOR);
    }

    private static Long currentUserId() {
        return UserContextHolder.currentUserIdOrNull();
    }

    private static List<RagRecall.RecallHit> mapHits(SearchHits<KnowledgeCardDocument> hits, RagRecall.RecallSource source) {
        List<RagRecall.RecallHit> out = new ArrayList<>();
        for (SearchHit<KnowledgeCardDocument> hit : hits.getSearchHits()) {
            KnowledgeCardDocument doc = hit.getContent();
            if (doc == null || isBlank(doc.getContent())) {
                continue;
            }
            String id = doc.getCardId() == null
                    ? hit.getId()
                    : "card:" + doc.getCardId();
            out.add(new RagRecall.RecallHit(id, toFactText(doc), hit.getScore(), source));
        }
        return out;
    }

    /**
     * 将卡片结构压平为事实文本；注入给模型时保留标题语义，但不暴露 ES 字段细节。
     */
    private static String toFactText(KnowledgeCardDocument doc) {
        StringBuilder text = new StringBuilder();
        if (!isBlank(doc.getTitle())) {
            text.append("知识卡片《").append(doc.getTitle().trim()).append("》：");
        }
        text.append(doc.getContent().trim());
        if (doc.getKeywords() != null && !doc.getKeywords().isEmpty()) {
            text.append(" 关键词：").append(String.join("、", doc.getKeywords()));
        }
        return text.toString();
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
