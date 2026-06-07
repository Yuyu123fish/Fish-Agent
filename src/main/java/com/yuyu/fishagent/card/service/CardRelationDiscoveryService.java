package com.yuyu.fishagent.card.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.fishagent.card.dto.ConfirmRelationRequest;
import com.yuyu.fishagent.card.dto.DiscoverResult;
import com.yuyu.fishagent.card.dto.RelationSuggestion;
import com.yuyu.fishagent.card.entity.CardKeyword;
import com.yuyu.fishagent.card.entity.CardRelation;
import com.yuyu.fishagent.card.entity.Keyword;
import com.yuyu.fishagent.card.entity.KeywordRelation;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import com.yuyu.fishagent.card.mapper.CardKeywordMapper;
import com.yuyu.fishagent.card.mapper.CardRelationMapper;
import com.yuyu.fishagent.card.mapper.KeywordMapper;
import com.yuyu.fishagent.card.mapper.KeywordRelationMapper;
import com.yuyu.fishagent.card.mapper.KnowledgeCardMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多信号卡片关联发现：关键词重叠、分组一致、关键词层次/关系扩展和 embedding 召回共同投票。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardRelationDiscoveryService {

    private static final float KEYWORD_WEIGHT = 0.35f;
    private static final float GROUP_WEIGHT = 0.20f;
    private static final float ANCESTOR_WEIGHT = 0.20f;
    private static final float EMBEDDING_WEIGHT = 0.25f;
    private static final float SUGGEST_THRESHOLD = 0.45f;
    private static final int MAX_SUGGESTIONS = 50;
    private static final int MAX_EMBEDDING_CANDIDATES = 100;

    private final KnowledgeCardMapper knowledgeCardMapper;
    private final CardRelationMapper cardRelationMapper;
    private final CardKeywordMapper cardKeywordMapper;
    private final KeywordMapper keywordMapper;
    private final KeywordRelationMapper keywordRelationMapper;
    private final KnowledgeCardService knowledgeCardService;
    private final KnowledgeCardEsSyncService esSyncService;

    public DiscoverResult discoverRelations(Long userId) {
        if (userId == null) {
            throw new IllegalStateException("未登录");
        }
        List<KnowledgeCard> cards = knowledgeCardMapper.selectList(Wrappers.<KnowledgeCard>lambdaQuery()
                .eq(KnowledgeCard::getUserId, userId)
                .eq(KnowledgeCard::getStatus, KnowledgeCard.STATUS_CONFIRMED));
        if (cards.size() < 2) {
            return new DiscoverResult(List.of(), 0);
        }

        Map<Long, KnowledgeCard> cardById = new HashMap<>();
        for (KnowledgeCard card : cards) {
            cardById.put(card.getId(), card);
        }
        Map<Long, Set<Long>> keywordIdsByCard = loadKeywordIdsByCard(userId);
        Map<Long, Keyword> keywordById = loadKeywordById(userId);
        Map<Long, Set<Long>> expandedKeywords = buildExpandedKeywordMap(userId, keywordIdsByCard, keywordById);
        Set<String> existingEdges = loadExistingEdges(userId);
        Set<CardPair> candidates = buildCandidates(cards, keywordIdsByCard);
        Map<String, Float> embeddingScores = candidates.size() <= MAX_EMBEDDING_CANDIDATES
                ? loadEmbeddingScores(userId, candidates, cardById)
                : Map.of();

        List<RelationSuggestion> suggestions = new ArrayList<>();
        for (CardPair pair : candidates) {
            if (existingEdges.contains(pair.key())) {
                continue;
            }
            KnowledgeCard a = cardById.get(pair.a());
            KnowledgeCard b = cardById.get(pair.b());
            if (a == null || b == null) {
                continue;
            }
            Set<Long> aKeywords = keywordIdsByCard.getOrDefault(a.getId(), Set.of());
            Set<Long> bKeywords = keywordIdsByCard.getOrDefault(b.getId(), Set.of());
            float keywordScore = jaccard(aKeywords, bKeywords);
            boolean sameGroup = sameGroup(a, b);
            float ancestorScore = jaccard(
                    expandedKeywords.getOrDefault(a.getId(), Set.of()),
                    expandedKeywords.getOrDefault(b.getId(), Set.of())
            );
            float embeddingScore = embeddingScores.getOrDefault(pair.key(), 0.0f);
            float total = keywordScore * KEYWORD_WEIGHT
                    + (sameGroup ? GROUP_WEIGHT : 0)
                    + ancestorScore * ANCESTOR_WEIGHT
                    + embeddingScore * EMBEDDING_WEIGHT;
            if (total < SUGGEST_THRESHOLD) {
                continue;
            }
            List<String> reasons = buildReasons(a, b, aKeywords, bKeywords, keywordById, keywordScore, ancestorScore, embeddingScore);
            suggestions.add(new RelationSuggestion(
                    a.getId(),
                    a.getTitle(),
                    b.getId(),
                    b.getTitle(),
                    CardRelation.TYPE_RELATED_TO,
                    round(total),
                    reasons
            ));
        }

        List<RelationSuggestion> top = suggestions.stream()
                .sorted(Comparator.comparing(RelationSuggestion::confidence).reversed())
                .limit(MAX_SUGGESTIONS)
                .toList();
        return new DiscoverResult(top, top.size());
    }

    @Transactional
    public void confirmDiscovered(Long userId, List<ConfirmRelationRequest> requests) {
        if (userId == null) {
            throw new IllegalStateException("未登录");
        }
        if (requests == null || requests.isEmpty()) {
            return;
        }
        for (ConfirmRelationRequest request : requests.stream().limit(100).toList()) {
            if (request.fromCardId() == null || request.toCardId() == null || request.fromCardId().equals(request.toCardId())) {
                continue;
            }
            // addRelation 内部会校验两端卡片归属。
            knowledgeCardService.addRelation(request.fromCardId(), request.toCardId(), request.relationType());
        }
    }

    private Map<Long, Set<Long>> loadKeywordIdsByCard(Long userId) {
        Map<Long, Set<Long>> out = new HashMap<>();
        for (CardKeyword link : cardKeywordMapper.selectByUserId(userId)) {
            out.computeIfAbsent(link.getCardId(), id -> new LinkedHashSet<>()).add(link.getKeywordId());
        }
        return out;
    }

    private Map<Long, Keyword> loadKeywordById(Long userId) {
        Map<Long, Keyword> out = new HashMap<>();
        for (Keyword keyword : keywordMapper.selectByUserId(userId)) {
            out.put(keyword.getId(), keyword);
        }
        return out;
    }

    private Map<Long, Set<Long>> buildExpandedKeywordMap(Long userId,
                                                         Map<Long, Set<Long>> keywordIdsByCard,
                                                         Map<Long, Keyword> keywordById) {
        List<KeywordRelation> relations = keywordRelationMapper.selectByUserId(userId);
        Map<Long, Set<Long>> relationNeighbors = new HashMap<>();
        for (KeywordRelation relation : relations) {
            relationNeighbors.computeIfAbsent(relation.getFromKeywordId(), id -> new LinkedHashSet<>()).add(relation.getToKeywordId());
            relationNeighbors.computeIfAbsent(relation.getToKeywordId(), id -> new LinkedHashSet<>()).add(relation.getFromKeywordId());
        }

        Map<Long, Set<Long>> out = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : keywordIdsByCard.entrySet()) {
            Set<Long> expanded = new LinkedHashSet<>();
            for (Long keywordId : entry.getValue()) {
                Keyword keyword = keywordById.get(keywordId);
                if (keyword != null && keyword.getParentId() != null) {
                    expanded.add(keyword.getParentId());
                    Keyword parent = keywordById.get(keyword.getParentId());
                    if (parent != null && parent.getParentId() != null) {
                        expanded.add(parent.getParentId());
                    }
                }
                expanded.addAll(relationNeighbors.getOrDefault(keywordId, Set.of()));
            }
            out.put(entry.getKey(), expanded);
        }
        return out;
    }

    private Set<String> loadExistingEdges(Long userId) {
        Set<String> out = new HashSet<>();
        List<CardRelation> relations = cardRelationMapper.selectList(Wrappers.<CardRelation>lambdaQuery()
                .inSql(CardRelation::getFromCardId, "SELECT id FROM knowledge_card WHERE user_id = " + userId)
                .inSql(CardRelation::getToCardId, "SELECT id FROM knowledge_card WHERE user_id = " + userId));
        for (CardRelation relation : relations) {
            out.add(CardPair.of(relation.getFromCardId(), relation.getToCardId()).key());
        }
        return out;
    }

    private Set<CardPair> buildCandidates(List<KnowledgeCard> cards, Map<Long, Set<Long>> keywordIdsByCard) {
        Set<CardPair> candidates = new LinkedHashSet<>();
        Map<String, List<Long>> byGroup = new HashMap<>();
        for (KnowledgeCard card : cards) {
            if (card.getGroupName() != null && !card.getGroupName().isBlank()) {
                byGroup.computeIfAbsent(card.getGroupName().trim(), k -> new ArrayList<>()).add(card.getId());
            }
        }
        for (List<Long> ids : byGroup.values()) {
            addPairs(ids, candidates);
        }

        Map<Long, List<Long>> byKeyword = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : keywordIdsByCard.entrySet()) {
            for (Long keywordId : entry.getValue()) {
                byKeyword.computeIfAbsent(keywordId, k -> new ArrayList<>()).add(entry.getKey());
            }
        }
        for (List<Long> ids : byKeyword.values()) {
            addPairs(ids, candidates);
        }
        return candidates;
    }

    private Map<String, Float> loadEmbeddingScores(Long userId, Set<CardPair> candidates, Map<Long, KnowledgeCard> cardById) {
        Map<Long, Set<Long>> wanted = new HashMap<>();
        for (CardPair pair : candidates) {
            wanted.computeIfAbsent(pair.a(), id -> new HashSet<>()).add(pair.b());
        }
        Map<String, Float> scores = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : wanted.entrySet()) {
            KnowledgeCard card = cardById.get(entry.getKey());
            if (card == null) {
                continue;
            }
            for (KnowledgeCardEsSyncService.CardVectorHit hit : esSyncService.findSimilarConfirmed(userId, card, 30)) {
                if (entry.getValue().contains(hit.cardId())) {
                    scores.put(CardPair.of(card.getId(), hit.cardId()).key(), Math.max(0.0f, Math.min(1.0f, hit.score())));
                }
            }
        }
        return scores;
    }

    private List<String> buildReasons(KnowledgeCard a, KnowledgeCard b,
                                      Set<Long> aKeywords, Set<Long> bKeywords,
                                      Map<Long, Keyword> keywordById,
                                      float keywordScore, float ancestorScore, float embeddingScore) {
        List<String> reasons = new ArrayList<>();
        if (sameGroup(a, b)) {
            reasons.add("同组(" + a.getGroupName().trim() + ")");
        }
        List<String> sharedKeywords = sharedKeywordNames(aKeywords, bKeywords, keywordById);
        if (!sharedKeywords.isEmpty()) {
            reasons.add("共享关键词(" + String.join("、", sharedKeywords.stream().limit(5).toList()) + ")");
        } else if (keywordScore > 0) {
            reasons.add("关键词重叠");
        }
        if (ancestorScore > 0) {
            reasons.add("关键词层次/关系相近");
        }
        if (embeddingScore > 0) {
            reasons.add("语义相似(" + String.format("%.2f", embeddingScore) + ")");
        }
        if (reasons.isEmpty()) {
            reasons.add("多信号综合匹配");
        }
        return reasons;
    }

    private static List<String> sharedKeywordNames(Set<Long> a, Set<Long> b, Map<Long, Keyword> keywordById) {
        Set<Long> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        return intersection.stream()
                .map(keywordById::get)
                .filter(k -> k != null && k.getName() != null)
                .map(Keyword::getName)
                .toList();
    }

    private static void addPairs(List<Long> ids, Set<CardPair> out) {
        List<Long> unique = ids.stream().distinct().toList();
        for (int i = 0; i < unique.size(); i++) {
            for (int j = i + 1; j < unique.size(); j++) {
                out.add(CardPair.of(unique.get(i), unique.get(j)));
            }
        }
    }

    private static float jaccard(Set<Long> a, Set<Long> b) {
        if (a == null || b == null || (a.isEmpty() && b.isEmpty())) {
            return 0.0f;
        }
        Set<Long> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<Long> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0f : (float) intersection.size() / union.size();
    }

    private static boolean sameGroup(KnowledgeCard a, KnowledgeCard b) {
        if (a.getGroupName() == null || b.getGroupName() == null) {
            return false;
        }
        String ga = a.getGroupName().trim();
        String gb = b.getGroupName().trim();
        return !ga.isEmpty() && ga.equals(gb);
    }

    private static float round(float value) {
        return Math.round(value * 1000f) / 1000f;
    }

    private record CardPair(Long a, Long b) {
        static CardPair of(Long x, Long y) {
            return x <= y ? new CardPair(x, y) : new CardPair(y, x);
        }

        String key() {
            return a + ":" + b;
        }
    }
}
