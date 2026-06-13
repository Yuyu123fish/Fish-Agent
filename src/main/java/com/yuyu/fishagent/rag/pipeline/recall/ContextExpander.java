package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.rag.config.KnowledgeProperties;
import com.yuyu.fishagent.rag.config.RagProperties;
import com.yuyu.fishagent.rag.document.PublicKnowledgeDocument;
import com.yuyu.fishagent.rag.document.UserMemoryDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命中文档切片的邻块扩展器。
 *
 * <p>检索排序仍只看中心命中；扩展阶段只把同一文档前后 N 个 chunk 拼回渲染内容，缓解答案缺少上下文的问题。
 * 对话记忆和知识卡片没有稳定 chunk 坐标，默认跳过。</p>
 */
@Slf4j
public class ContextExpander {

    private final RagProperties ragProperties;
    private final KnowledgeProperties knowledgeProperties;
    private final ObjectProvider<ElasticsearchOperations> operationsProvider;

    public ContextExpander(RagProperties ragProperties,
                           KnowledgeProperties knowledgeProperties,
                           ObjectProvider<ElasticsearchOperations> operationsProvider) {
        this.ragProperties = ragProperties;
        this.knowledgeProperties = knowledgeProperties;
        this.operationsProvider = operationsProvider;
    }

    public List<RagRecall.RecallHit> expand(List<RagRecall.RecallHit> hits) {
        RagProperties.ExpandNeighbors cfg = ragProperties.getExpandNeighbors();
        if (hits == null || hits.isEmpty() || !cfg.isEnabled()) {
            return hits == null ? List.of() : hits;
        }
        ElasticsearchOperations operations = operationsProvider.getIfAvailable();
        if (operations == null) {
            return hits;
        }
        int span = Math.max(0, cfg.getNeighborSpan());
        if (span == 0) {
            return hits;
        }
        List<RagRecall.RecallHit> out = new ArrayList<>(hits.size());
        for (RagRecall.RecallHit hit : hits) {
            if (hit.docId() == null || hit.docId().isBlank() || hit.chunkIndex() == null || "记忆".equals(hit.effectiveSourceLabel())) {
                out.add(hit);
                continue;
            }
            try {
                out.add(mergeNeighbors(hit, fetchNeighbors(operations, hit, span)));
            } catch (Exception e) {
                log.debug("[ContextExpander] 邻块扩展失败 id={}, docId={}: {}", hit.id(), hit.docId(), e.getMessage());
                out.add(hit);
            }
        }
        return out;
    }

    static RagRecall.RecallHit mergeNeighbors(RagRecall.RecallHit center, List<RagRecall.RecallHit> neighbors) {
        Map<String, RagRecall.RecallHit> byKey = new LinkedHashMap<>();
        if (neighbors != null) {
            for (RagRecall.RecallHit hit : neighbors) {
                String key = RagRecall.dedupKey(hit);
                if (key != null) {
                    byKey.putIfAbsent(key, hit);
                }
            }
        }
        String centerKey = RagRecall.dedupKey(center);
        if (centerKey != null) {
            byKey.putIfAbsent(centerKey, center);
        }
        List<RagRecall.RecallHit> ordered = byKey.values().stream()
                .sorted(Comparator.comparing(
                        RagRecall.RecallHit::chunkIndex,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
        StringBuilder text = new StringBuilder();
        for (RagRecall.RecallHit hit : ordered) {
            if (hit.content() == null || hit.content().isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(hit.content().trim());
        }
        return center.withContent(text.isEmpty() ? center.content() : text.toString());
    }

    private List<RagRecall.RecallHit> fetchNeighbors(ElasticsearchOperations operations, RagRecall.RecallHit hit, int span) {
        int from = Math.max(0, hit.chunkIndex() - span);
        int to = hit.chunkIndex() + span;
        if ("公开".equals(hit.effectiveSourceLabel()) || "官方".equals(hit.effectiveSourceLabel())) {
            return fetchPublicNeighbors(operations, hit, from, to);
        }
        return fetchUserNeighbors(operations, hit, from, to);
    }

    private List<RagRecall.RecallHit> fetchUserNeighbors(ElasticsearchOperations operations, RagRecall.RecallHit hit, int from, int to) {
        Long uid = UserContextHolder.currentUserIdOrNull();
        if (uid == null) {
            return List.of();
        }
        NativeQuery query = NativeQuery.builder()
                .withMaxResults(to - from + 1)
                .withSort(Sort.by(Sort.Direction.ASC, "chunk_index"))
                .withQuery(q -> q.bool(b -> b
                        .filter(f -> f.term(t -> t.field("user_id").value(String.valueOf(uid))))
                        .filter(f -> f.term(t -> t.field("doc_id").value(hit.docId())))
                        .filter(f -> f.range(r -> r.number(n -> n.field("chunk_index").gte((double) from).lte((double) to))))))
                .build();
        return operations.search(query, UserMemoryDocument.class, IndexCoordinates.of(knowledgeProperties.getUserKnowledgeIndexName()))
                .getSearchHits().stream()
                .map(SearchHit::getContent)
                .filter(doc -> doc != null && doc.getContent() != null && !doc.getContent().isBlank())
                .map(doc -> new RagRecall.RecallHit(doc.getId(), doc.getContent().trim(), hit.score(), hit.source(),
                        hit.effectiveSourceLabel(), hit.authority(), doc.bestCreatedAt(), doc.getDocId(), doc.getChunkIndex()))
                .toList();
    }

    private List<RagRecall.RecallHit> fetchPublicNeighbors(ElasticsearchOperations operations, RagRecall.RecallHit hit, int from, int to) {
        NativeQuery query = NativeQuery.builder()
                .withMaxResults(to - from + 1)
                .withSort(Sort.by(Sort.Direction.ASC, "chunk_index"))
                .withQuery(q -> q.bool(b -> b
                        .filter(f -> f.term(t -> t.field("doc_id").value(hit.docId())))
                        .filter(f -> f.range(r -> r.number(n -> n.field("chunk_index").gte((double) from).lte((double) to))))))
                .build();
        return operations.search(query, PublicKnowledgeDocument.class, IndexCoordinates.of(knowledgeProperties.getPublicIndexName()))
                .getSearchHits().stream()
                .map(SearchHit::getContent)
                .filter(doc -> doc != null && doc.getContent() != null && !doc.getContent().isBlank())
                .map(doc -> new RagRecall.RecallHit(doc.getId(), doc.getContent().trim(), hit.score(), hit.source(),
                        hit.effectiveSourceLabel(), hit.authority(), doc.bestCreatedAt(), doc.getDocId(), doc.getChunkIndex()))
                .toList();
    }
}
