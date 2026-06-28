package com.yuyu.fishagent.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SystemMessage 段拼装顺序 [v6.4]：缓存友好——稳定段(instruction)前置、易变的时钟锚点置末。
 *
 * <p>动机：DeepSeek prompt cache 严格按前缀匹配。时钟行含秒级时间戳、跨 turn 必变，
 * 若排在 SystemMessage 首个 token，会让整段 system 前缀每轮全量 miss。把它挪到末尾后，
 * instruction + 各稳定段成为可命中的前缀，只有易变尾巴按全价计费。</p>
 */
class ChatServiceSystemBlockAssemblyTest {

    @Test
    void instructionIsPrefixAndClockLineGoesLastForCacheFriendliness() {
        String assembled = ChatService.assembleSystemBlock(
                "人设指令", "当前会话时间：2026-06-27 14:30:00。",
                "结构化摘要", "关键摘录", "会话状态", "RAG片段");

        // instruction 是首个 token（缓存前缀起点）
        assertThat(assembled.indexOf("人设指令")).isZero();
        // 顺序：人设 → 摘要 → 摘录 → 状态 → RAG → 时钟
        assertThat(assembled.indexOf("结构化摘要")).isGreaterThan(assembled.indexOf("人设指令"));
        assertThat(assembled.indexOf("关键摘录")).isGreaterThan(assembled.indexOf("结构化摘要"));
        assertThat(assembled.indexOf("会话状态")).isGreaterThan(assembled.indexOf("关键摘录"));
        assertThat(assembled.indexOf("RAG片段")).isGreaterThan(assembled.indexOf("会话状态"));
        // 时钟锚点置末（秒级时间戳每轮变，绝不能落在前缀）
        int clock = assembled.indexOf("当前会话时间");
        assertThat(clock).isGreaterThan(assembled.indexOf("RAG片段"));
        assertThat(assembled.lastIndexOf("当前会话时间")).isEqualTo(clock); // 只出现一次
        assertThat(assembled).endsWith("当前会话时间：2026-06-27 14:30:00。");
    }

    @Test
    void blankSectionsSkippedAndClockStillLast() {
        String assembled = ChatService.assembleSystemBlock("人设", "时间T", "", "", "", "");

        assertThat(assembled).startsWith("人设");
        assertThat(assembled).endsWith("时间T");
    }

    @Test
    void blankInstructionAndNoSectionsYieldsJustClock() {
        // instruction 空 + 无增强段：只剩时钟行，仍能正常拼出（不报错、无多余分隔符）
        String assembled = ChatService.assembleSystemBlock("", "时间T", "", "", "", "");

        assertThat(assembled).isEqualTo("时间T");
    }
}
