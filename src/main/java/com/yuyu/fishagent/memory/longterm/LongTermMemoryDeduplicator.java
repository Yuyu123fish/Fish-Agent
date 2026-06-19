package com.yuyu.fishagent.memory.longterm;

import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.rag.document.UserMemoryDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期事实 embedding 余弦查重。
 * <p>先用 ES kNN 找候选邻居，再用原始向量手动重算 cosine，避免直接依赖 ES 归一化分数。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LongTermMemoryDeduplicator {

    private final MemoryProperties properties;

    public List<SimilarFact> findSimilar(ElasticsearchOperations operations, IndexCoordinates index,
                                         String userId, List<Float> candidateVector, int requestedK) {
        MemoryProperties.Dedup cfg = properties.getDedup();
        if (!cfg.isEnabled() || candidateVector == null || candidateVector.isEmpty()
                || userId == null || userId.isBlank()) {
            return List.of();
        }
        try {
            int k = Math.max(1, requestedK);
            int numCandidates = Math.max(k, cfg.getNumCandidates());
            NativeQuery query = NativeQuery.builder()
                    .withMaxResults(k)
                    .withKnnSearches(kn -> kn
                            .field("embedding")
                            .queryVector(candidateVector)
                            .k(k)
                            .numCandidates(numCandidates)
                            .filter(f -> f.bool(b -> b
                                    .filter(ff -> ff.term(t -> t.field("user_id").value(userId)))
                                    .filter(ff -> ff.term(t -> t.field("source_type").value("chat")))
                                    .mustNot(mn -> mn.term(t -> t.field("superseded").value(true))))))
                    .build();

            List<SimilarFact> similar = new ArrayList<>();
            for (SearchHit<UserMemoryDocument> hit : operations.search(query, UserMemoryDocument.class, index).getSearchHits()) {
                UserMemoryDocument doc = hit.getContent();
                if (doc != null && doc.getEmbedding() != null && !doc.getEmbedding().isEmpty()) {
                    double score = cosine(candidateVector, doc.getEmbedding());
                    if (score >= cfg.getSimilarityThreshold() && doc.getContent() != null && !doc.getContent().isBlank()) {
                        String id = hit.getId() != null ? hit.getId() : doc.getId();
                        similar.add(new SimilarFact(id, doc.getContent().trim(), doc.getCreatedAt(), score));
                    }
                }
            }
            if (!similar.isEmpty()) {
                log.debug("[LongTermMemoryDeduplicator] 命中相似事实 count={}, threshold={}", similar.size(), cfg.getSimilarityThreshold());
            }
            return similar;
        } catch (Exception e) {
            log.warn("[LongTermMemoryDeduplicator] 查重失败，按无相似事实处理: {}", e.getMessage());
            return List.of();
        }
    }

    public static double cosine(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.size() != b.size() || a.isEmpty()) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
