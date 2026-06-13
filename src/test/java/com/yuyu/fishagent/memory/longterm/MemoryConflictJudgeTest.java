package com.yuyu.fishagent.memory.longterm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryConflictJudgeTest {

    @Test
    void parseVerdictAcceptsJsonAndPlainText() {
        assertThat(MemoryConflictJudge.parseVerdict("{\"verdict\":\"SAME\"}"))
                .isEqualTo(MemoryConflictJudge.Verdict.SAME);
        assertThat(MemoryConflictJudge.parseVerdict(" conflict "))
                .isEqualTo(MemoryConflictJudge.Verdict.CONFLICT);
        assertThat(MemoryConflictJudge.parseVerdict("NEITHER"))
                .isEqualTo(MemoryConflictJudge.Verdict.NEITHER);
    }

    @Test
    void parseVerdictFallsBackToNeitherForUnknownOutput() {
        assertThat(MemoryConflictJudge.parseVerdict("可能相关，但无法判断"))
                .isEqualTo(MemoryConflictJudge.Verdict.NEITHER);
        assertThat(MemoryConflictJudge.parseVerdict(null))
                .isEqualTo(MemoryConflictJudge.Verdict.NEITHER);
    }
}
