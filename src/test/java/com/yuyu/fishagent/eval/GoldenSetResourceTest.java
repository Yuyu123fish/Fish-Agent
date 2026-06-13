package com.yuyu.fishagent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenSetResourceTest {

    @Test
    void goldenSetContainsBenchmarkSizedSeedCases() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream input = getClass().getResourceAsStream("/eval/golden-rag.json")) {
            List<GoldenSet.Case> cases = objectMapper.readValue(input, new TypeReference<>() {
            });

            assertThat(cases).hasSizeGreaterThanOrEqualTo(20);
            assertThat(cases)
                    .allSatisfy(item -> assertThat(item.candidates()).hasSizeGreaterThanOrEqualTo(2));
            assertThat(cases)
                    .allSatisfy(item -> assertThat(item.candidates())
                            .anySatisfy(candidate -> {
                                assertThat(candidate.authority()).isEqualTo(1.0);
                                assertThat(candidate.relevance()).isGreaterThanOrEqualTo(3);
                            }));
        }
    }
}
