package com.yuyu.fishagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenSummaryResourceTest {

    @Test
    void goldenSummarySetContainsLongSessionSeedCases() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream input = getClass().getResourceAsStream("/eval/golden-summary.json")) {
            SummaryGoldenSet goldenSet = objectMapper.readValue(input, SummaryGoldenSet.class);

            assertThat(goldenSet.cases()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(goldenSet.cases())
                    .allSatisfy(item -> {
                        assertThat(item.id()).isNotBlank();
                        assertThat(item.windowSize()).isPositive();
                        assertThat(item.session()).hasSizeGreaterThan(150);
                        assertThat(item.expectedKeyEntities()).isNotEmpty();
                        assertThat(item.expectedActiveTopics()).isNotEmpty();
                        assertThat(item.mustNotLose()).isNotEmpty();
                    });
        }
    }
}
