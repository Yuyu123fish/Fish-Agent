package com.yuyu.fishagent.memory.longterm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
}
