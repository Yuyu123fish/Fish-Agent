package com.yuyu.fishagent.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkKMeansClustererTest {

    @Test
    void clusterKeepsNearbyVectorsTogether() {
        List<List<Float>> vectors = List.of(
                List.of(1.0f, 0.0f),
                List.of(0.98f, 0.02f),
                List.of(0.0f, 1.0f),
                List.of(0.02f, 0.98f)
        );

        int[] labels = ChunkKMeansClusterer.cluster(vectors, 2, 20);

        assertThat(labels).hasSize(4);
        assertThat(labels[0]).isEqualTo(labels[1]);
        assertThat(labels[2]).isEqualTo(labels[3]);
        assertThat(labels[0]).isNotEqualTo(labels[2]);
    }

    @Test
    void clusterFallsBackToSingleGroupWhenKIsTooSmall() {
        List<List<Float>> vectors = List.of(
                List.of(1.0f, 0.0f),
                List.of(0.0f, 1.0f)
        );

        int[] labels = ChunkKMeansClusterer.cluster(vectors, 1, 20);

        assertThat(labels).containsExactly(0, 0);
    }
}
