package com.yuyu.fishagent.rag.pipeline.recall;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryAgeLabelTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-06-13T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void formatsRecentAndOldMemoryAge() {
        assertThat(MemoryAgeLabel.format(Instant.parse("2026-06-13T11:55:00Z").toEpochMilli(), FIXED))
                .isEqualTo("刚刚");
        assertThat(MemoryAgeLabel.format(Instant.parse("2026-06-12T12:00:00Z").toEpochMilli(), FIXED))
                .isEqualTo("1天前");
        assertThat(MemoryAgeLabel.format(Instant.parse("2026-05-13T12:00:00Z").toEpochMilli(), FIXED))
                .isEqualTo("1个月前");
    }
}
