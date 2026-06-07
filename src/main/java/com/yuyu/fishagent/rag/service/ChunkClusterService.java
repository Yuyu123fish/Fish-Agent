package com.yuyu.fishagent.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.card.document.KnowledgeCardDocument;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import com.yuyu.fishagent.memory.longterm.LongTermMemoryDeduplicator;
import com.yuyu.fishagent.rag.config.KnowledgeProperties;
import com.yuyu.fishagent.rag.document.KnowledgeChunkDocument;
import com.yuyu.fishagent.rag.dto.ChunkGroupItemVO;
import com.yuyu.fishagent.rag.dto.ChunkGroupVO;
import com.yuyu.fishagent.rag.dto.ChunkItemVO;
import com.yuyu.fishagent.rag.dto.ChunkListVO;
import com.yuyu.fishagent.rag.dto.RelatedCardVO;
import com.yuyu.fishagent.rag.dto.RelatedChunkVO;
import com.yuyu.fishagent.rag.entity.DocumentMetadata;
import com.yuyu.fishagent.rag.mapper.DocumentMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.util.ObjectBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 知识库切片可视化服务。
 *
 * <p>职责边界：Controller 只做 HTTP 参数接入；本服务负责权限校验、ES 切片读取、向量聚类、Redis 缓存和切片↔卡片动态关联。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkClusterService {

    private static final String CACHE_PREFIX = "chunk-cluster:";
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final int CLUSTER_MIN_CHUNKS = 10;
    private static final int MAX_GROUPS = 8;
    private static final int MAX_CHUNKS_FROM_ES = 10_000;
    private static final int KMEANS_ITERATIONS = 20;
    private static final int VECTOR_CANDIDATES = 80;
    private static final double RELATED_THRESHOLD = 0.70;

    private final DocumentMetadataMapper documentMetadataMapper;
    private final KnowledgeProperties knowledgeProperties;
    private final ObjectProvider<ElasticsearchOperations> operationsProvider;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;

    public ChunkGroupVO getGroups(String taskId, Long currentUserId, boolean admin) {
        DocumentMetadata row = loadVisibleSuccessDocument(taskId, currentUserId, admin);
        ClusterPayload payload = loadClusterPayload(row);
        return toGroupVO(row, payload);
    }

    public ChunkListVO getChunks(String taskId, Integer groupIndex, String keyword, int page, int size,
                                 Long currentUserId, boolean admin) {
        DocumentMetadata row = loadVisibleSuccessDocument(taskId, currentUserId, admin);
        ClusterPayload payload = loadClusterPayload(row);
        Set<Integer> allowedIndexes = allowedChunkIndexes(payload, groupIndex);
        List<KnowledgeChunkDocument> chunks = loadChunks(row, normalizeKeyword(keyword)).stream()
                .filter(chunk -> groupIndex == null || allowedIndexes.contains(safeChunkIndex(chunk)))
                .sorted(Comparator.comparingInt(ChunkClusterService::safeChunkIndex))
                .toList();

        int pageSize = Math.min(100, Math.max(1, size));
        int current = Math.max(1, page);
        int from = Math.min(chunks.size(), (current - 1) * pageSize);
        int to = Math.min(chunks.size(), from + pageSize);
        List<ChunkItemVO> records = chunks.subList(from, to).stream()
                .map(chunk -> new ChunkItemVO(
                        safeChunkIndex(chunk),
                        nullToBlank(chunk.getContent()),
                        nullToBlank(chunk.getContent()).length(),
                        countRelatedCards(row.getUserId(), chunk)))
                .toList();
        return new ChunkListVO(row.getTaskId(), records, (long) chunks.size());
    }

    public List<RelatedCardVO> getRelatedCards(String taskId, Integer chunkIndex, Long currentUserId, boolean admin) {
        DocumentMetadata row = loadVisibleSuccessDocument(taskId, currentUserId, admin);
        KnowledgeChunkDocument chunk = findChunkByIndex(row, chunkIndex);
        if (chunk == null || chunk.getEmbedding() == null || chunk.getEmbedding().isEmpty()) {
            return List.of();
        }
        return searchRelatedCards(row.getUserId(), chunk.getEmbedding(), 3);
    }

    /**
     * 卡片详情页反向查源文档切片：仅 source_type=knowledge 且 source_id=taskId 时生效。
     */
    public List<RelatedChunkVO> findRelatedChunksForCard(KnowledgeCard card) {
        if (card == null || card.getId() == null
                || !KnowledgeCard.SOURCE_KNOWLEDGE.equals(card.getSourceType())
                || card.getSourceId() == null || card.getSourceId().isBlank()) {
            return List.of();
        }
        DocumentMetadata row = documentMetadataMapper.selectOne(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, card.getSourceId().trim()));
        if (row == null || !DocumentMetadata.STATUS_SUCCESS.equals(row.getStatus())) {
            return List.of();
        }
        if (DocumentMetadata.SCOPE_PRIVATE.equals(row.getScopeType()) && !Objects.equals(row.getUserId(), card.getUserId())) {
            return List.of();
        }
        ElasticsearchOperations ops = operationsProvider.getIfAvailable();
        if (ops == null) {
            return List.of();
        }
        KnowledgeCardDocument cardDoc = ops.get(String.valueOf(card.getId()), KnowledgeCardDocument.class);
        if (cardDoc == null || cardDoc.getEmbedding() == null || cardDoc.getEmbedding().isEmpty()) {
            return List.of();
        }
        try {
            NativeQuery query = NativeQuery.builder()
                    .withMaxResults(5)
                    .withKnnSearches(kn -> kn
                            .field("embedding")
                            .queryVector(cardDoc.getEmbedding())
                            .k(5)
                            .numCandidates(VECTOR_CANDIDATES)
                            .filter(f -> chunkFilter(f, row)))
                    .build();
            SearchHits<KnowledgeChunkDocument> hits = ops.search(query, KnowledgeChunkDocument.class, indexOf(row));
            List<RelatedChunkVO> out = new ArrayList<>();
            for (SearchHit<KnowledgeChunkDocument> hit : hits.getSearchHits()) {
                KnowledgeChunkDocument chunk = hit.getContent();
                if (chunk == null || chunk.getEmbedding() == null || chunk.getEmbedding().isEmpty()) {
                    continue;
                }
                double cosine = LongTermMemoryDeduplicator.cosine(cardDoc.getEmbedding(), chunk.getEmbedding());
                if (cosine >= RELATED_THRESHOLD) {
                    out.add(new RelatedChunkVO(
                            row.getTaskId(),
                            row.getFileName(),
                            safeChunkIndex(chunk),
                            preview(chunk.getContent(), 180),
                            round(cosine)));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[ChunkCluster] 卡片关联切片查询失败 cardId={}: {}", card.getId(), e.getMessage());
            return List.of();
        }
    }

    public void evictClusterCache(String taskId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null && taskId != null && !taskId.isBlank()) {
            redis.delete(CACHE_PREFIX + taskId.trim());
        }
    }

    private ClusterPayload loadClusterPayload(DocumentMetadata row) {
        String cacheKey = CACHE_PREFIX + row.getTaskId();
        ClusterPayload cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<KnowledgeChunkDocument> chunks = loadChunks(row, null);
        ClusterPayload payload = buildClusterPayload(row, chunks);
        writeCache(cacheKey, payload);
        return payload;
    }

    private ClusterPayload buildClusterPayload(DocumentMetadata row, List<KnowledgeChunkDocument> chunks) {
        List<KnowledgeChunkDocument> validChunks = chunks.stream()
                .filter(chunk -> chunk.getEmbedding() != null && !chunk.getEmbedding().isEmpty())
                .toList();
        if (chunks.size() < CLUSTER_MIN_CHUNKS || validChunks.size() < CLUSTER_MIN_CHUNKS) {
            return new ClusterPayload(row.getTaskId(), row.getFileName(), buildSummary(row, chunks),
                    List.of(new ClusterItem(0, "全部切片", chunks.stream().map(ChunkClusterService::safeChunkIndex).toList())));
        }

        int k = Math.min(MAX_GROUPS, Math.max(2, chunks.size() / 5));
        int[] labels = ChunkKMeansClusterer.cluster(validChunks.stream().map(KnowledgeChunkDocument::getEmbedding).toList(), k, KMEANS_ITERATIONS);
        Map<Integer, List<Integer>> byGroup = new LinkedHashMap<>();
        for (int i = 0; i < labels.length; i++) {
            byGroup.computeIfAbsent(labels[i], ignored -> new ArrayList<>()).add(safeChunkIndex(validChunks.get(i)));
        }
        List<Integer> missingEmbeddingIndexes = chunks.stream()
                .filter(chunk -> chunk.getEmbedding() == null || chunk.getEmbedding().isEmpty())
                .map(ChunkClusterService::safeChunkIndex)
                .toList();
        if (!missingEmbeddingIndexes.isEmpty()) {
            byGroup.computeIfAbsent(0, ignored -> new ArrayList<>()).addAll(missingEmbeddingIndexes);
        }
        Map<Integer, KnowledgeChunkDocument> byChunkIndex = chunks.stream()
                .collect(Collectors.toMap(ChunkClusterService::safeChunkIndex, c -> c, (a, b) -> a, LinkedHashMap::new));
        AiClusterText aiText = generateAiClusterText(row, byGroup, byChunkIndex);
        List<ClusterItem> groups = new ArrayList<>();
        int displayIndex = 0;
        for (Map.Entry<Integer, List<Integer>> entry : byGroup.entrySet()) {
            List<Integer> indexes = entry.getValue().stream().sorted().toList();
            String fallbackTitle = fallbackGroupTitle(displayIndex, indexes, byChunkIndex);
            groups.add(new ClusterItem(displayIndex, safeTitle(aiText.titles().getOrDefault(entry.getKey(), fallbackTitle)), indexes));
            displayIndex++;
        }
        return new ClusterPayload(row.getTaskId(), row.getFileName(), aiText.summary(), groups);
    }

    private List<KnowledgeChunkDocument> loadChunks(DocumentMetadata row, String keyword) {
        ElasticsearchOperations ops = operationsProvider.getIfAvailable();
        if (ops == null) {
            return List.of();
        }
        try {
            NativeQuery query = NativeQuery.builder()
                    .withMaxResults(MAX_CHUNKS_FROM_ES)
                    .withSort(Sort.by(Sort.Order.asc("chunk_index")))
                    .withQuery(q -> q.bool(b -> {
                        b.filter(f -> chunkFilter(f, row));
                        if (keyword != null) {
                            b.must(m -> m.match(mt -> mt.field("content").query(keyword)));
                        }
                        return b;
                    }))
                    .build();
            return ops.search(query, KnowledgeChunkDocument.class, indexOf(row)).getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("[ChunkCluster] ES 切片查询失败 taskId={}: {}", row.getTaskId(), e.getMessage());
            return List.of();
        }
    }

    private KnowledgeChunkDocument findChunkByIndex(DocumentMetadata row, Integer chunkIndex) {
        if (chunkIndex == null || chunkIndex < 0) {
            return null;
        }
        ElasticsearchOperations ops = operationsProvider.getIfAvailable();
        if (ops == null) {
            return null;
        }
        try {
            NativeQuery query = NativeQuery.builder()
                    .withMaxResults(1)
                    .withQuery(q -> q.bool(b -> b
                            .filter(f -> chunkFilter(f, row))
                            .filter(f -> f.term(t -> t.field("chunk_index").value(chunkIndex)))))
                    .build();
            return ops.search(query, KnowledgeChunkDocument.class, indexOf(row)).getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[ChunkCluster] 查询单个切片失败 taskId={}, chunkIndex={}: {}", row.getTaskId(), chunkIndex, e.getMessage());
            return null;
        }
    }

    private int countRelatedCards(Long userId, KnowledgeChunkDocument chunk) {
        if (chunk == null || chunk.getEmbedding() == null || chunk.getEmbedding().isEmpty()) {
            return 0;
        }
        return searchRelatedCards(userId, chunk.getEmbedding(), 5).size();
    }

    private List<RelatedCardVO> searchRelatedCards(Long userId, List<Float> vector, int topK) {
        ElasticsearchOperations ops = operationsProvider.getIfAvailable();
        if (ops == null || userId == null || vector == null || vector.isEmpty()) {
            return List.of();
        }
        try {
            int k = Math.max(1, topK);
            NativeQuery query = NativeQuery.builder()
                    .withMaxResults(k)
                    .withKnnSearches(kn -> kn
                            .field("embedding")
                            .queryVector(vector)
                            .k(k)
                            .numCandidates(VECTOR_CANDIDATES)
                            .filter(f -> f.bool(b -> b
                                    .filter(ff -> ff.term(t -> t.field("userId").value(userId)))
                                    .filter(ff -> ff.term(t -> t.field("status").value(KnowledgeCard.STATUS_CONFIRMED))))))
                    .build();
            SearchHits<KnowledgeCardDocument> hits = ops.search(query, KnowledgeCardDocument.class);
            List<RelatedCardVO> out = new ArrayList<>();
            for (SearchHit<KnowledgeCardDocument> hit : hits.getSearchHits()) {
                KnowledgeCardDocument doc = hit.getContent();
                if (doc == null || doc.getCardId() == null || doc.getEmbedding() == null || doc.getEmbedding().isEmpty()) {
                    continue;
                }
                double cosine = LongTermMemoryDeduplicator.cosine(vector, doc.getEmbedding());
                if (cosine >= RELATED_THRESHOLD) {
                    out.add(new RelatedCardVO(doc.getCardId(), doc.getTitle(), doc.getCardType(), round(cosine)));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[ChunkCluster] 切片关联卡片查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    private DocumentMetadata loadVisibleSuccessDocument(String taskId, Long currentUserId, boolean admin) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        DocumentMetadata row = documentMetadataMapper.selectOne(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, taskId.trim()));
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "文档不存在");
        }
        if (!admin && (currentUserId == null || !Objects.equals(currentUserId, row.getUserId()))) {
            throw new ResponseStatusException(FORBIDDEN, "无权查看该文档切片");
        }
        if (!DocumentMetadata.STATUS_SUCCESS.equals(row.getStatus())) {
            throw new ResponseStatusException(BAD_REQUEST, "文档尚未解析完成");
        }
        return row;
    }

    private IndexCoordinates indexOf(DocumentMetadata row) {
        if (DocumentMetadata.SCOPE_PUBLIC.equals(row.getScopeType())) {
            return IndexCoordinates.of(knowledgeProperties.getPublicIndexName());
        }
        return IndexCoordinates.of(knowledgeProperties.getUserKnowledgeIndexName());
    }

    private ObjectBuilder<Query> chunkFilter(Query.Builder q, DocumentMetadata row) {
        return q.bool(b -> {
            b.filter(f -> f.term(t -> t.field("doc_id").value(row.getTaskId())));
            if (DocumentMetadata.SCOPE_PRIVATE.equals(row.getScopeType())) {
                b.filter(f -> f.term(t -> t.field("user_id").value(String.valueOf(row.getUserId()))));
            }
            return b;
        });
    }

    private ClusterPayload readCache(String key) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(key);
            return raw == null ? null : objectMapper.readValue(raw, ClusterPayload.class);
        } catch (Exception e) {
            log.warn("[ChunkCluster] Redis 缓存读取失败 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, ClusterPayload payload) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(payload), CACHE_TTL);
        } catch (Exception e) {
            log.warn("[ChunkCluster] Redis 缓存写入失败 key={}: {}", key, e.getMessage());
        }
    }

    private ChunkGroupVO toGroupVO(DocumentMetadata row, ClusterPayload payload) {
        List<ChunkGroupItemVO> groups = payload.groups().stream()
                .map(g -> new ChunkGroupItemVO(g.groupIndex(), g.title(), g.chunkIndexes().size()))
                .toList();
        int total = payload.groups().stream().mapToInt(g -> g.chunkIndexes().size()).sum();
        return new ChunkGroupVO(row.getTaskId(), row.getFileName(), payload.summary(), total, groups);
    }

    private Set<Integer> allowedChunkIndexes(ClusterPayload payload, Integer groupIndex) {
        if (groupIndex == null) {
            return payload.groups().stream()
                    .flatMap(g -> g.chunkIndexes().stream())
                    .collect(Collectors.toSet());
        }
        return payload.groups().stream()
                .filter(g -> Objects.equals(g.groupIndex(), groupIndex))
                .findFirst()
                .map(g -> Set.copyOf(g.chunkIndexes()))
                .orElse(Set.of());
    }

    private AiClusterText generateAiClusterText(DocumentMetadata row, Map<Integer, List<Integer>> byGroup,
                                                Map<Integer, KnowledgeChunkDocument> byChunkIndex) {
        Map<Integer, String> fallbackTitles = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : byGroup.entrySet()) {
            fallbackTitles.put(entry.getKey(), fallbackGroupTitle(entry.getKey(), entry.getValue(), byChunkIndex));
        }
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null || byGroup.isEmpty()) {
            return new AiClusterText(buildSummary(row, byChunkIndex.values().stream().toList()), fallbackTitles);
        }
        try {
            String raw = model.call(buildClusterPrompt(row, byGroup, byChunkIndex));
            JsonNode root = objectMapper.readTree(extractJson(raw));
            String summary = root.path("summary").asText(buildSummary(row, byChunkIndex.values().stream().toList()));
            Map<Integer, String> titles = new LinkedHashMap<>(fallbackTitles);
            JsonNode titleNode = root.path("titles");
            if (titleNode.isArray()) {
                for (JsonNode node : titleNode) {
                    int group = node.path("groupIndex").asInt(-1);
                    String title = node.path("title").asText(null);
                    if (group >= 0 && title != null && !title.isBlank()) {
                        titles.put(group, title);
                    }
                }
            }
            return new AiClusterText(summary, titles);
        } catch (Exception e) {
            log.warn("[ChunkCluster] AI 分组标题生成失败 taskId={}，使用兜底标题: {}", row.getTaskId(), e.getMessage());
            return new AiClusterText(buildSummary(row, byChunkIndex.values().stream().toList()), fallbackTitles);
        }
    }

    private String buildClusterPrompt(DocumentMetadata row, Map<Integer, List<Integer>> byGroup,
                                      Map<Integer, KnowledgeChunkDocument> byChunkIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为文档切片主题分组生成标题和摘要。只输出 JSON，不要 Markdown。\n")
                .append("格式：{\"summary\":\"200字以内摘要\",\"titles\":[{\"groupIndex\":0,\"title\":\"10字以内标题\"}]}\n")
                .append("文档名：").append(row.getFileName()).append("\n");
        for (Map.Entry<Integer, List<Integer>> entry : byGroup.entrySet()) {
            sb.append("groupIndex=").append(entry.getKey()).append(":\n");
            entry.getValue().stream().limit(3).forEach(idx -> {
                KnowledgeChunkDocument chunk = byChunkIndex.get(idx);
                sb.append("- ").append(preview(chunk == null ? "" : chunk.getContent(), 100)).append("\n");
            });
        }
        return sb.toString();
    }

    private String buildSummary(DocumentMetadata row, List<KnowledgeChunkDocument> chunks) {
        String sample = chunks.stream()
                .sorted(Comparator.comparingInt(ChunkClusterService::safeChunkIndex))
                .limit(5)
                .map(c -> preview(c.getContent(), 80))
                .collect(Collectors.joining("；"));
        if (sample.isBlank()) {
            return "暂无可用于生成摘要的切片内容。";
        }
        return "文档「" + row.getFileName() + "」包含 " + (row.getChunkCount() == null ? chunks.size() : row.getChunkCount())
                + " 个切片，主要内容包括：" + preview(sample, 180);
    }

    private static String fallbackGroupTitle(int groupIndex, List<Integer> indexes, Map<Integer, KnowledgeChunkDocument> byChunkIndex) {
        String sample = indexes.stream()
                .map(byChunkIndex::get)
                .filter(Objects::nonNull)
                .map(KnowledgeChunkDocument::getContent)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse("");
        String title = preview(sample, 10);
        return title.isBlank() ? "主题 " + (groupIndex + 1) : title;
    }

    private static String extractJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private static int safeChunkIndex(KnowledgeChunkDocument chunk) {
        return chunk == null || chunk.getChunkIndex() == null ? 0 : chunk.getChunkIndex();
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String s = keyword.trim();
        return s.isBlank() ? null : s;
    }

    private static String safeTitle(String title) {
        String s = title == null ? "" : title.trim();
        if (s.isBlank()) {
            return "未命名主题";
        }
        return s.length() > 16 ? s.substring(0, 16) : s;
    }

    private static String preview(String content, int limit) {
        String s = nullToBlank(content).replaceAll("\\s+", " ").trim();
        return s.length() <= limit ? s : s.substring(0, limit) + "...";
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /**
     * Redis 内部缓存模型：包含每个分组的 chunkIndex 列表，公开接口会隐藏该内部细节。
     */
    public record ClusterPayload(String taskId, String fileName, String summary, List<ClusterItem> groups) {
    }

    public record ClusterItem(Integer groupIndex, String title, List<Integer> chunkIndexes) {
    }

    public record AiClusterText(String summary, Map<Integer, String> titles) {
    }
}
