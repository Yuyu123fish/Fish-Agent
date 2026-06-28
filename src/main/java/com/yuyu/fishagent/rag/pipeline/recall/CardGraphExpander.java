package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.card.dto.CardRelationVO;
import com.yuyu.fishagent.card.entity.CardRelation;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import com.yuyu.fishagent.card.mapper.CardRelationMapper;
import com.yuyu.fishagent.card.mapper.KnowledgeCardMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱邻居扩展器：对召回命中的卡片沿 {@code card_relation} 取 1 跳邻居注入上下文，
 * 让 precedes / derived_from / contains / related 等关系真正进入模型上下文，而非仅作详情页装饰。
 *
 * <p>在精排后的 expand 阶段调用：对每张命中卡片取其图邻居，按关系类型加权（语义关系 &gt; related_to），
 * 与已命中卡片去重、按「种子分 × 关系权重」排序、截取上限 N，作为来源标注「图谱」的事实注入。
 * 邻居绕过精排，因为它的相关性来自与种子的图关系，而非与查询文本的匹配。</p>
 */
@Slf4j
public class CardGraphExpander {

    private static final String CARD_ID_PREFIX = "card:";
    private static final String SOURCE_LABEL_GRAPH = "图谱";
    private static final double GRAPH_AUTHORITY = 0.6;
    private static final double WEIGHT_SEMANTIC = 0.9; // precedes / derived_from / contains
    private static final double WEIGHT_RELATED = 0.6;  // related_to
    private static final int DEFAULT_MAX_NEIGHBORS = 4;

    private final CardRelationMapper cardRelationMapper;
    private final KnowledgeCardMapper knowledgeCardMapper;
    private final int maxNeighbors;

    public CardGraphExpander(CardRelationMapper cardRelationMapper, KnowledgeCardMapper knowledgeCardMapper) {
        this(cardRelationMapper, knowledgeCardMapper, DEFAULT_MAX_NEIGHBORS);
    }

    public CardGraphExpander(CardRelationMapper cardRelationMapper, KnowledgeCardMapper knowledgeCardMapper, int maxNeighbors) {
        this.cardRelationMapper = cardRelationMapper;
        this.knowledgeCardMapper = knowledgeCardMapper;
        this.maxNeighbors = Math.max(0, maxNeighbors);
    }

    public List<RagRecall.RecallHit> expand(List<RagRecall.RecallHit> hits) {
        return expand(hits, UserContextHolder.currentUserIdOrNull());
    }

    List<RagRecall.RecallHit> expand(List<RagRecall.RecallHit> hits, Long userId) {
        if (hits == null || hits.isEmpty() || userId == null || maxNeighbors == 0) {
            return hits == null ? List.of() : hits;
        }
        Set<String> existingIds = new HashSet<>();
        List<RagRecall.RecallHit> seeds = new ArrayList<>();
        for (RagRecall.RecallHit hit : hits) {
            if (hit.id() != null) {
                existingIds.add(hit.id());
            }
            if (isCardHit(hit)) {
                seeds.add(hit);
            }
        }
        if (seeds.isEmpty()) {
            return hits;
        }

        List<NeighborCandidate> candidates = collectNeighborCandidates(userId, seeds, existingIds);
        if (candidates.isEmpty()) {
            return hits;
        }
        candidates.sort(Comparator.comparingDouble(
                (NeighborCandidate c) -> weight(c.relationType()) * c.seedScore()).reversed());
        List<NeighborCandidate> chosen = candidates.stream().limit(maxNeighbors).toList();

        Map<Long, KnowledgeCard> cardById = fetchConfirmedCards(userId,
                chosen.stream().map(NeighborCandidate::cardId).toList());
        if (cardById.isEmpty()) {
            return hits;
        }

        List<RagRecall.RecallHit> out = new ArrayList<>(hits);
        for (NeighborCandidate candidate : chosen) {
            KnowledgeCard card = cardById.get(candidate.cardId());
            if (card == null) {
                continue;
            }
            double score = weight(candidate.relationType()) * candidate.seedScore();
            out.add(new RagRecall.RecallHit(cardKey(candidate.cardId()), toFactText(card), score,
                    RagRecall.RecallSource.VECTOR, SOURCE_LABEL_GRAPH, GRAPH_AUTHORITY,
                    null, null, null, card.getTitle()));
        }
        return out;
    }

    private List<NeighborCandidate> collectNeighborCandidates(Long userId,
                                                              List<RagRecall.RecallHit> seeds,
                                                              Set<String> existingIds) {
        List<NeighborCandidate> candidates = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (RagRecall.RecallHit seed : seeds) {
            Long cardId = parseCardId(seed.id());
            if (cardId == null) {
                continue;
            }
            List<CardRelationVO> relations;
            try {
                relations = cardRelationMapper.selectRelationsForCard(userId, cardId);
            } catch (Exception e) {
                log.debug("[CardGraphExpander] 查询图邻居失败 cardId={}: {}", cardId, e.getMessage());
                continue;
            }
            if (relations == null) {
                continue;
            }
            for (CardRelationVO relation : relations) {
                Long neighborId = relation.cardId();
                if (neighborId == null || seen.contains(neighborId) || existingIds.contains(cardKey(neighborId))) {
                    continue;
                }
                seen.add(neighborId);
                candidates.add(new NeighborCandidate(neighborId, relation.relationType(), seed.score()));
            }
        }
        return candidates;
    }

    private Map<Long, KnowledgeCard> fetchConfirmedCards(Long userId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<KnowledgeCard> cards;
        try {
            cards = knowledgeCardMapper.selectBatchIds(ids);
        } catch (Exception e) {
            log.debug("[CardGraphExpander] 批量查询卡片失败 ids={}: {}", ids, e.getMessage());
            return Map.of();
        }
        Map<Long, KnowledgeCard> out = new LinkedHashMap<>();
        if (cards == null) {
            return out;
        }
        for (KnowledgeCard card : cards) {
            if (card == null || card.getId() == null) {
                continue;
            }
            if (userId.equals(card.getUserId()) && KnowledgeCard.STATUS_CONFIRMED.equals(card.getStatus())) {
                out.put(card.getId(), card);
            }
        }
        return out;
    }

    private static double weight(String relationType) {
        if (CardRelation.TYPE_PRECEDES.equals(relationType)
                || CardRelation.TYPE_DERIVED_FROM.equals(relationType)
                || CardRelation.TYPE_CONTAINS.equals(relationType)) {
            return WEIGHT_SEMANTIC;
        }
        return WEIGHT_RELATED;
    }

    private static boolean isCardHit(RagRecall.RecallHit hit) {
        return hit != null && hit.id() != null && hit.id().startsWith(CARD_ID_PREFIX);
    }

    private static Long parseCardId(String id) {
        if (id == null || !id.startsWith(CARD_ID_PREFIX)) {
            return null;
        }
        try {
            return Long.valueOf(id.substring(CARD_ID_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cardKey(Long cardId) {
        return CARD_ID_PREFIX + cardId;
    }

    private static String toFactText(KnowledgeCard card) {
        StringBuilder sb = new StringBuilder();
        if (card.getTitle() != null && !card.getTitle().isBlank()) {
            sb.append("知识卡片《").append(card.getTitle().trim()).append("》：");
        }
        sb.append(card.getContent() == null ? "" : card.getContent().trim());
        if (card.getKeywords() != null && !card.getKeywords().isEmpty()) {
            sb.append(" 关键词：").append(String.join("、", card.getKeywords()));
        }
        return sb.toString();
    }

    private record NeighborCandidate(Long cardId, String relationType, double seedScore) {
    }
}
