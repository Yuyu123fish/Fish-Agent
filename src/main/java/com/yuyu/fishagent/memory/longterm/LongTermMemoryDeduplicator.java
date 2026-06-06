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

    public boolean isDuplicate(ElasticsearchOperations operations, IndexCoordinates index,
                               String userId, List<Float> candidateVector) {
        MemoryProperties.Dedup cfg = properties.getDedup();
        if (!cfg.isEnabled() || candidateVector == null || candidateVector.isEmpty()
                || userId == null || userId.isBlank()) {
            return false;
        }
        try {
            int k = Math.max(1, cfg.getK());
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
                                    .filter(ff -> ff.term(t -> t.field("source_type").value("chat"))))))
                    .build();

            List<List<Float>> neighbors = new ArrayList<>();
            for (SearchHit<UserMemoryDocument> hit : operations.search(query, UserMemoryDocument.class, index).getSearchHits()) {
                UserMemoryDocument doc = hit.getContent();
                if (doc != null && doc.getEmbedding() != null && !doc.getEmbedding().isEmpty()) {
                    neighbors.add(doc.getEmbedding());
                }
            }
            double max = maxCosine(candidateVector, neighbors);
            boolean duplicate = max >= cfg.getSimilarityThreshold();
            if (duplicate) {
                log.debug("[LongTermMemoryDeduplicator] 命中重复事实 maxCos={}, threshold={}", max, cfg.getSimilarityThreshold());
            }
            return duplicate;
        } catch (Exception e) {
            log.warn("[LongTermMemoryDeduplicator] 查重失败，按非重复处理: {}", e.getMessage());
            return false;
        }
    }

    public static double maxCosine(List<Float> query, List<List<Float>> candidates) {
        double max = 0.0;
        if (candidates == null) {
            return max;
        }
        for (List<Float> candidate : candidates) {
            max = Math.max(max, cosine(query, candidate));
        }
        return max;
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
