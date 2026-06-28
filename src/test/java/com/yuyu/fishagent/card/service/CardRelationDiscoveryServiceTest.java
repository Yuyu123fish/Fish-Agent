package com.yuyu.fishagent.card.service;

import com.yuyu.fishagent.card.entity.KnowledgeCard;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖关联发现的 embedding 信号"降级而非整体置零"治理：
 * 候选对超过上限时，按其余三信号取 top-N 对参与 embedding 评分，头部候选保留语义信号。
 */
class CardRelationDiscoveryServiceTest {

    @Test
    void selectEmbeddingScope_returnsAllCandidates_whenUnderCap() {
        Set<CardRelationDiscoveryService.CardPair> candidates = new LinkedHashSet<>();
        candidates.add(CardRelationDiscoveryService.CardPair.of(1L, 2L));
        candidates.add(CardRelationDiscoveryService.CardPair.of(2L, 3L));

        Map<Long, Set<Long>> kw = new HashMap<>();
        kw.put(1L, Set.of(10L));
        kw.put(2L, Set.of(10L));
        kw.put(3L, Set.of(10L));
        Map<Long, KnowledgeCard> cardById = new HashMap<>();
        cardById.put(1L, card(1L, "Java"));
        cardById.put(2L, card(2L, "Java"));
        cardById.put(3L, card(3L, "Java"));

        Set<CardRelationDiscoveryService.CardPair> scope =
                CardRelationDiscoveryService.selectEmbeddingScope(candidates, kw, Map.of(), cardById);

        assertThat(scope).containsExactlyInAnyOrderElementsOf(candidates);
    }

    @Test
    void selectEmbeddingScope_keepsTopPairsByPartialScore_whenOverCap() {
        // 1 对共享关键词 + 同组的高分对，外加 101 对零分对 → 总数 102 超过上限 100
        Set<CardRelationDiscoveryService.CardPair> candidates = new LinkedHashSet<>();
        candidates.add(CardRelationDiscoveryService.CardPair.of(1L, 2L));
        for (long i = 3L; i <= 103L; i++) {
            candidates.add(CardRelationDiscoveryService.CardPair.of(1L, i));
        }

        Map<Long, Set<Long>> kw = new HashMap<>();
        kw.put(1L, Set.of(10L));
        kw.put(2L, Set.of(10L)); // 仅 1&2 共享关键词
        Map<Long, KnowledgeCard> cardById = new HashMap<>();
        cardById.put(1L, card(1L, "Java"));
        cardById.put(2L, card(2L, "Java")); // 与 1 同组
        for (long i = 3L; i <= 103L; i++) {
            cardById.put(i, card(i, "group-" + i)); // 各自独立分组
        }

        Set<CardRelationDiscoveryService.CardPair> scope =
                CardRelationDiscoveryService.selectEmbeddingScope(candidates, kw, Map.of(), cardById);

        assertThat(scope).hasSize(100); // 封顶到上限
        assertThat(scope).contains(CardRelationDiscoveryService.CardPair.of(1L, 2L)); // 高分对被保留
    }

    private static KnowledgeCard card(long id, String group) {
        KnowledgeCard c = new KnowledgeCard();
        c.setId(id);
        c.setUserId(7L);
        c.setGroupName(group);
        c.setStatus(KnowledgeCard.STATUS_CONFIRMED);
        return c;
    }
}
