package com.yuyu.fishagent.memory.shortterm;

import java.util.List;
import java.util.Map;

/**
 * 结构化短期记忆摘要，按话题、实体、待办意图和用户信号四个维度组织上下文。
 */
public record StructuredSummary(
        List<TopicSegment> activeTopics,
        Map<String, List<String>> keyEntities,
        List<String> pendingIntents,
        UserSignals userSignals
) {
}
