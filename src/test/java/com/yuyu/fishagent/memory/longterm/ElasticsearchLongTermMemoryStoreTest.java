package com.yuyu.fishagent.memory.longterm;

import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.rag.document.UserMemoryDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElasticsearchLongTermMemoryStoreTest {

    @Test
    void conflictFactSupersedesOldBeijingMemoryAndWritesShanghaiMemory() {
        Harness h = new Harness();
        SimilarFact beijing = new SimilarFact("old-beijing", "用户在北京上班", 1L, 0.97);
        when(h.embeddingModel.embed("用户调到上海工作")).thenReturn(new float[]{1f, 0f});
        when(h.deduplicator.findSimilar(any(), any(), eq("7"), eq(List.of(1f, 0f)), eq(5)))
                .thenReturn(List.of(beijing));
        when(h.conflictJudge.judge("用户调到上海工作", beijing))
                .thenReturn(MemoryConflictJudge.Verdict.CONFLICT);

        h.store.saveFacts(7L, "sid", List.of("用户调到上海工作"));

        ArgumentCaptor<UpdateQuery> update = ArgumentCaptor.forClass(UpdateQuery.class);
        verify(h.operations).update(update.capture(), any(IndexCoordinates.class));
        assertThat(update.getValue().getId()).isEqualTo("old-beijing");
        assertThat(update.getValue().getDocument()).containsEntry("superseded", true);

        ArgumentCaptor<UserMemoryDocument> saved = ArgumentCaptor.forClass(UserMemoryDocument.class);
        verify(h.operations).save(saved.capture(), any(IndexCoordinates.class));
        assertThat(saved.getValue().getContent()).isEqualTo("用户调到上海工作");
        assertThat(saved.getValue().isSuperseded()).isFalse();
    }

    @Test
    void sameFactIsDroppedWithoutWriting() {
        Harness h = new Harness();
        SimilarFact existing = new SimilarFact("old", "用户喜欢拿铁", 1L, 0.99);
        when(h.embeddingModel.embed("用户喜欢拿铁")).thenReturn(new float[]{1f});
        when(h.deduplicator.findSimilar(any(), any(), eq("7"), eq(List.of(1f)), eq(5)))
                .thenReturn(List.of(existing));
        when(h.conflictJudge.judge("用户喜欢拿铁", existing))
                .thenReturn(MemoryConflictJudge.Verdict.SAME);

        h.store.saveFacts(7L, "sid", List.of("用户喜欢拿铁"));

        verify(h.operations, never()).save(any(UserMemoryDocument.class), any(IndexCoordinates.class));
        verify(h.operations, never()).update(any(UpdateQuery.class), any(IndexCoordinates.class));
    }

    @Test
    void neitherFactWritesWithoutSupersedingOldMemory() {
        Harness h = new Harness();
        SimilarFact related = new SimilarFact("old", "用户在北京上班", 1L, 0.93);
        when(h.embeddingModel.embed("用户周末去上海旅游")).thenReturn(new float[]{0.5f});
        when(h.deduplicator.findSimilar(any(), any(), eq("7"), eq(List.of(0.5f)), eq(5)))
                .thenReturn(List.of(related));
        when(h.conflictJudge.judge("用户周末去上海旅游", related))
                .thenReturn(MemoryConflictJudge.Verdict.NEITHER);

        h.store.saveFacts(7L, "sid", List.of("用户周末去上海旅游"));

        verify(h.operations, never()).update(any(UpdateQuery.class), any(IndexCoordinates.class));
        verify(h.operations).save(any(UserMemoryDocument.class), any(IndexCoordinates.class));
    }

    private static final class Harness {
        final ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        final LongTermMemoryDeduplicator deduplicator = mock(LongTermMemoryDeduplicator.class);
        final MemoryConflictJudge conflictJudge = mock(MemoryConflictJudge.class);
        final ElasticsearchLongTermMemoryStore store;

        @SuppressWarnings("unchecked")
        Harness() {
            MemoryProperties properties = new MemoryProperties();
            ObjectProvider<ElasticsearchOperations> operationsProvider = mock(ObjectProvider.class);
            ObjectProvider<EmbeddingModel> embeddingProvider = mock(ObjectProvider.class);
            when(operationsProvider.getIfAvailable()).thenReturn(operations);
            when(embeddingProvider.getIfAvailable()).thenReturn(embeddingModel);
            store = new ElasticsearchLongTermMemoryStore(
                    operationsProvider,
                    embeddingProvider,
                    properties,
                    deduplicator,
                    conflictJudge);
        }
    }
}
