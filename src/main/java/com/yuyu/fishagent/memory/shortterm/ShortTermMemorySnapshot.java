package com.yuyu.fishagent.memory.shortterm;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;

import java.util.List;

/**
 * 短期记忆快照：结构化摘要 + 最近消息窗口 + 关键原文片段 + 增量计数。
 */
public record ShortTermMemorySnapshot(
        StructuredSummary structuredSummary,
        List<ChatMessageDTO> recentMessages,
        List<KeyExcerpt> keyExcerpts,
        int incrementalCount,
        long lastCompressedAt,
        int lastCompressedMessageCount
) {

    public ShortTermMemorySnapshot {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        keyExcerpts = keyExcerpts == null ? List.of() : List.copyOf(keyExcerpts);
        incrementalCount = Math.max(0, incrementalCount);
        lastCompressedAt = Math.max(0, lastCompressedAt);
        lastCompressedMessageCount = Math.max(0, lastCompressedMessageCount);
    }

    /**
     * 常用构造器：结构化快照但不指定压缩游标，适合空快照或测试。
     */
    public ShortTermMemorySnapshot(StructuredSummary structuredSummary,
                                   List<ChatMessageDTO> recentMessages,
                                   List<KeyExcerpt> keyExcerpts,
                                   int incrementalCount) {
        this(structuredSummary, recentMessages, keyExcerpts, incrementalCount, 0, 0);
    }

    /**
     * 兼容仅记录时间游标的结构化快照。
     */
    public ShortTermMemorySnapshot(StructuredSummary structuredSummary,
                                   List<ChatMessageDTO> recentMessages,
                                   List<KeyExcerpt> keyExcerpts,
                                   int incrementalCount,
                                   long lastCompressedAt) {
        this(structuredSummary, recentMessages, keyExcerpts, incrementalCount, lastCompressedAt, 0);
    }

    /**
     * 旧格式兼容构造器：把纯文本摘要包装为默认结构化摘要。
     */
    public ShortTermMemorySnapshot(String summary, List<ChatMessageDTO> recentMessages) {
        this(legacySummary(summary), recentMessages, List.of(), 0, 0, 0);
    }

    /**
     * 旧摘要带游标构造器：用于传统压缩入口写入迁移快照。
     */
    public ShortTermMemorySnapshot(String summary,
                                   List<ChatMessageDTO> recentMessages,
                                   long lastCompressedAt,
                                   int lastCompressedMessageCount) {
        this(legacySummary(summary), recentMessages, List.of(), 0,
                lastCompressedAt, lastCompressedMessageCount);
    }

    /**
     * 兼容旧调用点的纯文本摘要视图。新代码应优先使用 {@link #structuredSummary()}。
     */
    public String summary() {
        if (structuredSummary == null || structuredSummary.activeTopics() == null) {
            return "";
        }
        return structuredSummary.activeTopics().stream()
                .map(topic -> {
                    String name = topic.topic() == null || topic.topic().isBlank() ? "默认话题" : topic.topic();
                    String summary = topic.summary() == null ? "" : topic.summary();
                    return name + "：" + summary;
                })
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static StructuredSummary legacySummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return new StructuredSummary(
                List.of(new TopicSegment("默认话题", "ACTIVE", summary)),
                java.util.Map.of(),
                List.of(),
                new UserSignals("", "", List.of())
        );
    }
}
