package com.yuyu.fishagent.card.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import com.yuyu.fishagent.card.mapper.KnowledgeCardMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 ES↔MySQL 对账：纯集合差分逻辑 + 调度编排（回填缺 embedding / 缺失文档，清理孤儿文档）。
 */
class KnowledgeCardEsReconciliationServiceTest {

    @Test
    void diffReconciliation_backfillsMissingAndEmpty_removesOrphans() {
        // MySQL confirmed {1,2,3}；ES confirmed {1(无embedding),2,4(孤儿)}
        Set<Long> mysql = Set.of(1L, 2L, 3L);
        Set<Long> es = Set.of(1L, 2L, 4L);
        Set<Long> esMissingEmb = Set.of(1L);

        KnowledgeCardEsReconciliationService.ReconciliationDiff diff =
                KnowledgeCardEsReconciliationService.diffReconciliation(mysql, es, esMissingEmb);

        // 回填 = 缺 embedding 的 1 + ES 完全缺失的 3
        assertThat(diff.backfill()).containsExactlyInAnyOrder(1L, 3L);
        // 孤儿 = ES 有但 MySQL 没有的 4
        assertThat(diff.orphan()).containsExactlyInAnyOrder(4L);
    }

    @Test
    void reconcile_reSyncsBackfillCardsAndDeletesOrphans() {
        Harness h = new Harness();
        when(h.mapper.selectList(any(Wrapper.class))).thenReturn(List.of(card(1L), card(2L), card(3L)));
        when(h.esSync.listConfirmedCardIds(anyInt())).thenReturn(Set.of(1L, 2L, 4L));
        when(h.esSync.listConfirmedMissingEmbeddingCardIds(anyInt())).thenReturn(Set.of(1L));

        h.service.reconcile();

        ArgumentCaptor<KnowledgeCard> synced = ArgumentCaptor.forClass(KnowledgeCard.class);
        verify(h.esSync, times(2)).syncConfirmedQuietly(synced.capture());
        assertThat(synced.getAllValues()).extracting(KnowledgeCard::getId)
                .containsExactlyInAnyOrder(1L, 3L);
        verify(h.esSync).deleteQuietly(4L);
        verify(h.esSync, never()).deleteQuietly(1L);
    }

    private static KnowledgeCard card(long id) {
        KnowledgeCard c = new KnowledgeCard();
        c.setId(id);
        c.setUserId(7L);
        c.setTitle("t-" + id);
        c.setContent("c-" + id);
        c.setStatus(KnowledgeCard.STATUS_CONFIRMED);
        return c;
    }

    private static final class Harness {
        final KnowledgeCardMapper mapper = mock(KnowledgeCardMapper.class);
        final KnowledgeCardEsSyncService esSync = mock(KnowledgeCardEsSyncService.class);
        final KnowledgeCardEsReconciliationService service =
                new KnowledgeCardEsReconciliationService(mapper, esSync);
    }
}
