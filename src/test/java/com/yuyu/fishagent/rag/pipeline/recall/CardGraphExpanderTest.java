package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.card.dto.CardRelationVO;
import com.yuyu.fishagent.card.entity.CardRelation;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import com.yuyu.fishagent.card.mapper.CardRelationMapper;
import com.yuyu.fishagent.card.mapper.KnowledgeCardMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖知识图谱邻居扩展：命中卡片沿 card_relation 取 1 跳邻居，按关系类型加权、去重、截断后以「图谱」来源注入。
 */
class CardGraphExpanderTest {

    @Test
    void expand_addsOneHopNeighborsWithRelationWeightAndGraphLabel() {
        CardRelationMapper relMapper = mock(CardRelationMapper.class);
        KnowledgeCardMapper cardMapper = mock(KnowledgeCardMapper.class);
        CardGraphExpander expander = new CardGraphExpander(relMapper, cardMapper, 4);

        RagRecall.RecallHit seed = new RagRecall.RecallHit("card:1", "c1", 0.9, RagRecall.RecallSource.VECTOR);
        when(relMapper.selectRelationsForCard(eq(7L), eq(1L))).thenReturn(List.of(
                new CardRelationVO(1L, 2L, "JVM 内存模型", "concept", "new", CardRelation.TYPE_PRECEDES, 0.8f, "outgoing"),
                new CardRelationVO(2L, 3L, "GC 调优", "topic", "new", CardRelation.TYPE_RELATED_TO, 0.7f, "outgoing")));
        when(cardMapper.selectBatchIds(any())).thenReturn(List.of(confirmedCard(2L, 7L), confirmedCard(3L, 7L)));

        List<RagRecall.RecallHit> out = expander.expand(List.of(seed), 7L);

        assertThat(out).extracting(RagRecall.RecallHit::id).contains("card:1", "card:2", "card:3");
        RagRecall.RecallHit precedes = out.stream().filter(h -> "card:2".equals(h.id())).findFirst().orElseThrow();
        assertThat(precedes.sourceLabel()).isEqualTo("图谱");
        assertThat(precedes.score()).isCloseTo(0.9 * 0.9, within(0.0001)); // 语义关系权重 0.9
        RagRecall.RecallHit related = out.stream().filter(h -> "card:3".equals(h.id())).findFirst().orElseThrow();
        assertThat(related.sourceLabel()).isEqualTo("图谱");
        assertThat(related.score()).isCloseTo(0.9 * 0.6, within(0.0001)); // related_to 权重 0.6
    }

    @Test
    void expand_skipsAlreadyPresentAndUnconfirmedNeighbors() {
        CardRelationMapper relMapper = mock(CardRelationMapper.class);
        KnowledgeCardMapper cardMapper = mock(KnowledgeCardMapper.class);
        CardGraphExpander expander = new CardGraphExpander(relMapper, cardMapper, 4);

        // card:2 已在召回里（去重）；card:4 未确认（过滤）
        RagRecall.RecallHit seed = new RagRecall.RecallHit("card:1", "c1", 0.9, RagRecall.RecallSource.VECTOR);
        RagRecall.RecallHit already = new RagRecall.RecallHit("card:2", "c2", 0.8, RagRecall.RecallSource.TEXT);
        when(relMapper.selectRelationsForCard(eq(7L), eq(1L))).thenReturn(List.of(
                new CardRelationVO(1L, 2L, "已存在", "concept", "new", CardRelation.TYPE_RELATED_TO, 0.7f, "outgoing"),
                new CardRelationVO(2L, 4L, "未确认", "concept", "new", CardRelation.TYPE_RELATED_TO, 0.7f, "outgoing")));
        when(cardMapper.selectBatchIds(any())).thenReturn(List.of(pendingCard(4L, 7L)));

        List<RagRecall.RecallHit> out = expander.expand(List.of(seed, already), 7L);

        assertThat(out).extracting(RagRecall.RecallHit::id).containsExactlyInAnyOrder("card:1", "card:2");
    }

    @Test
    void expand_capsAtMaxNeighbors() {
        CardRelationMapper relMapper = mock(CardRelationMapper.class);
        KnowledgeCardMapper cardMapper = mock(KnowledgeCardMapper.class);
        CardGraphExpander expander = new CardGraphExpander(relMapper, cardMapper, 2);

        RagRecall.RecallHit seed = new RagRecall.RecallHit("card:1", "c1", 0.9, RagRecall.RecallSource.VECTOR);
        when(relMapper.selectRelationsForCard(eq(7L), eq(1L))).thenReturn(List.of(
                neighbor(2L), neighbor(3L), neighbor(4L), neighbor(5L), neighbor(6L)));
        when(cardMapper.selectBatchIds(any())).thenReturn(List.of(
                confirmedCard(2L, 7L), confirmedCard(3L, 7L), confirmedCard(4L, 7L),
                confirmedCard(5L, 7L), confirmedCard(6L, 7L)));

        List<RagRecall.RecallHit> out = expander.expand(List.of(seed), 7L);

        // 种子 1 + 至多 2 个邻居
        assertThat(out).hasSize(3);
        assertThat(out).extracting(RagRecall.RecallHit::id).contains("card:1");
    }

    @Test
    void expand_passesThroughWhenNoCardSeeds() {
        CardRelationMapper relMapper = mock(CardRelationMapper.class);
        KnowledgeCardMapper cardMapper = mock(KnowledgeCardMapper.class);
        CardGraphExpander expander = new CardGraphExpander(relMapper, cardMapper, 4);

        RagRecall.RecallHit mem = new RagRecall.RecallHit("mem:1", "记忆命中", 0.5, RagRecall.RecallSource.TEXT);

        List<RagRecall.RecallHit> out = expander.expand(List.of(mem), 7L);

        assertThat(out).containsExactly(mem);
    }

    private static CardRelationVO neighbor(long id) {
        return new CardRelationVO(id, id, "t" + id, "concept", "new", CardRelation.TYPE_RELATED_TO, 0.7f, "outgoing");
    }

    private static KnowledgeCard confirmedCard(long id, long userId) {
        return card(id, userId, KnowledgeCard.STATUS_CONFIRMED);
    }

    private static KnowledgeCard pendingCard(long id, long userId) {
        return card(id, userId, KnowledgeCard.STATUS_PENDING);
    }

    private static KnowledgeCard card(long id, long userId, String status) {
        KnowledgeCard c = new KnowledgeCard();
        c.setId(id);
        c.setUserId(userId);
        c.setTitle("t-" + id);
        c.setContent("c-" + id);
        c.setStatus(status);
        return c;
    }
}
