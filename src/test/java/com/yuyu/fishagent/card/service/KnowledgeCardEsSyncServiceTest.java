package com.yuyu.fishagent.card.service;

import com.yuyu.fishagent.card.document.KnowledgeCardDocument;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import com.yuyu.fishagent.card.service.KnowledgeCardEsSyncService.CardVectorHit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 KnowledgeCardEsSyncService 的"静默失效"治理：
 * embedding 失败时文档仍写 ES、但 embedding 字段为 null（Spring Data 省略该字段，dense_vector 不再被空数组污染）。
 */
class KnowledgeCardEsSyncServiceTest {

    @Test
    void syncConfirmedQuietly_writesDocWithNullEmbedding_whenEmbeddingModelThrows() {
        Harness h = new Harness();
        when(h.embeddingModel.embed(any(String.class))).thenThrow(new RuntimeException("dashscope down"));

        h.service.syncConfirmedQuietly(card(1L));

        ArgumentCaptor<KnowledgeCardDocument> captor = ArgumentCaptor.forClass(KnowledgeCardDocument.class);
        verify(h.operations).save(captor.capture());
        // 关键断言：失败时不能是空数组 []，必须是 null（让 Spring Data 省略 dense_vector 字段）
        assertThat(captor.getValue().getEmbedding()).isNull();
    }

    @Test
    void syncConfirmedQuietly_writesDocWithNullEmbedding_whenEmbeddingModelUnavailable() {
        Harness h = new Harness();
        when(h.embeddingProvider.getIfAvailable()).thenReturn(null);

        h.service.syncConfirmedQuietly(card(1L));

        ArgumentCaptor<KnowledgeCardDocument> captor = ArgumentCaptor.forClass(KnowledgeCardDocument.class);
        verify(h.operations).save(captor.capture());
        assertThat(captor.getValue().getEmbedding()).isNull();
    }

    @Test
    void syncConfirmedQuietly_writesEmbedding_whenModelSucceeds() {
        Harness h = new Harness();
        when(h.embeddingModel.embed(any(String.class))).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        h.service.syncConfirmedQuietly(card(1L));

        ArgumentCaptor<KnowledgeCardDocument> captor = ArgumentCaptor.forClass(KnowledgeCardDocument.class);
        verify(h.operations).save(captor.capture());
        assertThat(captor.getValue().getEmbedding()).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    void findSimilarConfirmed_returnsEmptyAndSkipsSearch_whenEmbeddingFails() {
        Harness h = new Harness();
        when(h.embeddingModel.embed(any(String.class))).thenThrow(new RuntimeException("down"));

        List<CardVectorHit> hits = h.service.findSimilarConfirmed(7L, card(1L), 5);

        assertThat(hits).isEmpty();
        verify(h.operations, never()).search(any(Query.class), any(Class.class));
    }

    @Test
    void listConfirmedCardIds_extractsCardIdsFromHits() {
        Harness h = new Harness();
        SearchHits<KnowledgeCardDocument> hits = mockHits(100L, 101L);
        when(h.operations.search(any(Query.class), eq(KnowledgeCardDocument.class))).thenReturn(hits);

        Set<Long> ids = h.service.listConfirmedCardIds(500);

        assertThat(ids).containsExactlyInAnyOrder(100L, 101L);
    }

    @Test
    void listConfirmedMissingEmbeddingCardIds_extractsCardIdsFromHits() {
        Harness h = new Harness();
        SearchHits<KnowledgeCardDocument> hits = mockHits(200L, 201L);
        when(h.operations.search(any(Query.class), eq(KnowledgeCardDocument.class))).thenReturn(hits);

        Set<Long> ids = h.service.listConfirmedMissingEmbeddingCardIds(500);

        assertThat(ids).containsExactlyInAnyOrder(200L, 201L);
    }

    @SuppressWarnings("unchecked")
    private static SearchHits<KnowledgeCardDocument> mockHits(long... cardIds) {
        List<SearchHit<KnowledgeCardDocument>> list = new ArrayList<>();
        for (long id : cardIds) {
            KnowledgeCardDocument doc = new KnowledgeCardDocument();
            doc.setCardId(id);
            SearchHit<KnowledgeCardDocument> hit = mock(SearchHit.class);
            when(hit.getContent()).thenReturn(doc);
            list.add(hit);
        }
        SearchHits<KnowledgeCardDocument> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(list);
        return hits;
    }

    private static KnowledgeCard card(long id) {
        KnowledgeCard card = new KnowledgeCard();
        card.setId(id);
        card.setUserId(7L);
        card.setTitle("JVM GC");
        card.setContent("Garbage Collection 基础");
        card.setStatus(KnowledgeCard.STATUS_CONFIRMED);
        card.setKeywords(List.of("JVM", "GC"));
        return card;
    }

    @SuppressWarnings("unchecked")
    private static final class Harness {
        final ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        final ObjectProvider<ElasticsearchOperations> operationsProvider = mock(ObjectProvider.class);
        final ObjectProvider<EmbeddingModel> embeddingProvider = mock(ObjectProvider.class);
        final KnowledgeCardEsSyncService service;

        Harness() {
            when(operationsProvider.getIfAvailable()).thenReturn(operations);
            when(embeddingProvider.getIfAvailable()).thenReturn(embeddingModel);
            service = new KnowledgeCardEsSyncService(operationsProvider, embeddingProvider);
        }
    }
}
