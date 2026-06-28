package com.yuyu.fishagent.card.service;

import com.yuyu.fishagent.card.document.KnowledgeCardDocument;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识卡片 ES 同步与向量检索服务。
 *
 * <p>CRUD 服务只表达业务状态变化，embedding 生成、ES 写入和向量召回集中在这里，便于后续 RAG/图谱继续复用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeCardEsSyncService {

    private static final int DEFAULT_NUM_CANDIDATES = 80;

    private final ObjectProvider<ElasticsearchOperations> elasticsearchOperationsProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    /**
     * confirmed 卡片写入 ES；embedding 失败时不写 embedding 字段（返回 null，Spring Data 会省略该字段），
     * 文档仍以文本字段正常写入——全文检索不受影响，向量召回自动跳过该文档。
     * <p>避免向 dense_vector 字段写入空数组导致整篇文档被 ES 拒收（静默丢失全文能力）。</p>
     */
    public void syncConfirmedQuietly(KnowledgeCard card) {
        if (card == null || card.getId() == null) {
            return;
        }
        ElasticsearchOperations ops = elasticsearchOperationsProvider.getIfAvailable();
        if (ops == null) {
            log.debug("[KnowledgeCardES] ElasticsearchOperations 不可用，跳过同步 cardId={}", card.getId());
            return;
        }
        try {
            KnowledgeCardDocument doc = toDocument(card);
            doc.setEmbedding(embedForCardQuietly(card));
            ops.save(doc);
        } catch (Exception e) {
            log.warn("[KnowledgeCardES] ES 同步失败 cardId={}: {}", card.getId(), e.getMessage());
        }
    }

    /**
     * 删除 ES 文档；删除失败不阻塞 MySQL 主流程。
     */
    public void deleteQuietly(Long cardId) {
        if (cardId == null) {
            return;
        }
        ElasticsearchOperations ops = elasticsearchOperationsProvider.getIfAvailable();
        if (ops == null) {
            return;
        }
        try {
            ops.delete(String.valueOf(cardId), KnowledgeCardDocument.class);
        } catch (Exception e) {
            log.warn("[KnowledgeCardES] ES 删除失败 cardId={}: {}", cardId, e.getMessage());
        }
    }

    /**
     * 用卡片内容查找当前用户已有 confirmed 卡片，供 AI 提取后建立外部关联。
     */
    public List<CardVectorHit> findSimilarConfirmed(Long userId, KnowledgeCard card, int topK) {
        ElasticsearchOperations ops = elasticsearchOperationsProvider.getIfAvailable();
        if (ops == null || userId == null || card == null) {
            return List.of();
        }
        List<Float> vector = embedForCardQuietly(card);
        if (vector == null || vector.isEmpty()) {
            return List.of();
        }
        int k = Math.max(1, topK);
        try {
            NativeQuery query = NativeQuery.builder()
                    .withMaxResults(k)
                    .withKnnSearches(kn -> kn
                            .field("embedding")
                            .queryVector(vector)
                            .k(k)
                            .numCandidates(Math.max(DEFAULT_NUM_CANDIDATES, k))
                            .filter(f -> f.bool(b -> b
                                    .must(m -> m.term(t -> t.field("userId").value(userId)))
                                    .must(m -> m.term(t -> t.field("status").value(KnowledgeCard.STATUS_CONFIRMED))))))
                    .build();
            SearchHits<KnowledgeCardDocument> hits = ops.search(query, KnowledgeCardDocument.class);
            List<CardVectorHit> out = new ArrayList<>();
            for (SearchHit<KnowledgeCardDocument> hit : hits.getSearchHits()) {
                KnowledgeCardDocument doc = hit.getContent();
                if (doc == null || doc.getCardId() == null || doc.getCardId().equals(card.getId())) {
                    continue;
                }
                out.add(new CardVectorHit(doc.getCardId(), (float) hit.getScore()));
            }
            return out;
        } catch (Exception e) {
            log.warn("[KnowledgeCardES] 外部关联向量检索失败 cardId={}: {}", card.getId(), e.getMessage());
            return List.of();
        }
    }

    /** 对账用：列出 ES 中 status=confirmed 的卡片 ID。 */
    public Set<Long> listConfirmedCardIds(int size) {
        return queryConfirmedCardIds(size, false);
    }

    /** 对账用：列出 ES 中 status=confirmed 但缺 embedding 字段的卡片 ID。 */
    public Set<Long> listConfirmedMissingEmbeddingCardIds(int size) {
        return queryConfirmedCardIds(size, true);
    }

    private Set<Long> queryConfirmedCardIds(int size, boolean missingEmbedding) {
        ElasticsearchOperations ops = elasticsearchOperationsProvider.getIfAvailable();
        if (ops == null) {
            return Set.of();
        }
        try {
            NativeQuery query = NativeQuery.builder()
                    .withMaxResults(Math.max(1, size))
                    .withQuery(q -> missingEmbedding
                            ? q.bool(b -> b
                                    .must(m -> m.term(t -> t.field("status").value(KnowledgeCard.STATUS_CONFIRMED)))
                                    .mustNot(m -> m.exists(e -> e.field("embedding"))))
                            : q.term(t -> t.field("status").value(KnowledgeCard.STATUS_CONFIRMED)))
                    .build();
            SearchHits<KnowledgeCardDocument> hits = ops.search(query, KnowledgeCardDocument.class);
            Set<Long> out = new LinkedHashSet<>();
            for (SearchHit<KnowledgeCardDocument> hit : hits.getSearchHits()) {
                KnowledgeCardDocument doc = hit.getContent();
                if (doc != null && doc.getCardId() != null) {
                    out.add(doc.getCardId());
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[KnowledgeCardES] 对账查询失败 missingEmbedding={}: {}", missingEmbedding, e.getMessage());
            return Set.of();
        }
    }

    private List<Float> embedForCardQuietly(KnowledgeCard card) {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            log.debug("[KnowledgeCardES] EmbeddingModel 不可用，跳过 embedding cardId={}", card.getId());
            return null;
        }
        try {
            return toFloatList(embeddingModel.embed(cardEmbeddingText(card)));
        } catch (Exception e) {
            log.warn("[KnowledgeCardES] embedding 生成失败 cardId={}: {}", card.getId(), e.getMessage());
            return null;
        }
    }

    private static KnowledgeCardDocument toDocument(KnowledgeCard card) {
        KnowledgeCardDocument doc = new KnowledgeCardDocument();
        doc.setId(String.valueOf(card.getId()));
        doc.setCardId(card.getId());
        doc.setUserId(card.getUserId());
        doc.setTitle(card.getTitle());
        doc.setContent(card.getContent());
        doc.setKeywords(card.getKeywords() == null ? List.of() : card.getKeywords());
        doc.setCardType(card.getCardType());
        doc.setSourceType(card.getSourceType());
        doc.setStatus(card.getStatus());
        doc.setGroupName(card.getGroupName());
        doc.setCreatedAt(card.getCreatedAt());
        return doc;
    }

    private static String cardEmbeddingText(KnowledgeCard card) {
        String keywords = card.getKeywords() == null || card.getKeywords().isEmpty()
                ? ""
                : "\n关键词：" + String.join("、", card.getKeywords());
        return "标题：" + nullToBlank(card.getTitle())
                + "\n分组：" + nullToBlank(card.getGroupName())
                + keywords
                + "\n内容：" + nullToBlank(card.getContent());
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    /**
     * 向量召回命中的轻量结果。
     */
    public record CardVectorHit(Long cardId, float score) {
    }
}
