package com.yuyu.fishagent.memory.longterm;

import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.rag.document.UserMemoryDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LongTermMemoryDeduplicatorTest {

    @Test
    void cosineIdenticalIsOne() {
        List<Float> vector = List.of(1f, 2f, 3f);

        assertThat(LongTermMemoryDeduplicator.cosine(vector, vector)).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void cosineOrthogonalIsZero() {
        assertThat(LongTermMemoryDeduplicator.cosine(List.of(1f, 0f), List.of(0f, 1f)))
                .isCloseTo(0.0, within(1e-6));
    }

    @Test
    void cosineHandlesZeroVectorAndLengthMismatch() {
        assertThat(LongTermMemoryDeduplicator.cosine(List.of(0f, 0f), List.of(1f, 1f))).isEqualTo(0.0);
        assertThat(LongTermMemoryDeduplicator.cosine(List.of(1f), List.of(1f, 2f))).isEqualTo(0.0);
    }

    @Test
    void maxCosinePicksHighestNeighbor() {
        List<Float> query = List.of(1f, 0f);
        List<List<Float>> candidates = List.of(List.of(0f, 1f), List.of(1f, 0.05f));

        assertThat(LongTermMemoryDeduplicator.maxCosine(query, candidates)).isGreaterThan(0.99);
    }

    @Test
    void maxCosineEmptyCandidatesIsZero() {
        assertThat(LongTermMemoryDeduplicator.maxCosine(List.of(1f, 2f), List.of())).isEqualTo(0.0);
    }

    @Test
    void isDuplicateReturnsFalseWhenDisabled() {
        MemoryProperties props = new MemoryProperties();
        props.getDedup().setEnabled(false);
        LongTermMemoryDeduplicator dedup = new LongTermMemoryDeduplicator(props);
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);

        assertThat(dedup.isDuplicate(operations, IndexCoordinates.of("idx"), "user1", List.of(1f, 0f))).isFalse();
        verifyNoInteractions(operations);
    }

    @Test
    void isDuplicateReturnsFalseForBlankUserOrVector() {
        LongTermMemoryDeduplicator dedup = new LongTermMemoryDeduplicator(new MemoryProperties());
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);

        assertThat(dedup.isDuplicate(operations, IndexCoordinates.of("idx"), "user1", List.of())).isFalse();
        assertThat(dedup.isDuplicate(operations, IndexCoordinates.of("idx"), "user1", null)).isFalse();
        assertThat(dedup.isDuplicate(operations, IndexCoordinates.of("idx"), "  ", List.of(1f, 0f))).isFalse();
        verifyNoInteractions(operations);
    }

    @Test
    void isDuplicateReturnsFalseOnEsException() {
        LongTermMemoryDeduplicator dedup = new LongTermMemoryDeduplicator(new MemoryProperties());
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        when(operations.search(any(NativeQuery.class), eq(UserMemoryDocument.class), any(IndexCoordinates.class)))
                .thenThrow(new RuntimeException("ES down"));

        assertThat(dedup.isDuplicate(operations, IndexCoordinates.of("idx"), "user1", List.of(1f, 0f))).isFalse();
    }
}
