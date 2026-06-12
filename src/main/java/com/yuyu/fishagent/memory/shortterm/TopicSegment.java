package com.yuyu.fishagent.memory.shortterm;

/**
 * 单个话题段：追踪一个话题的状态和压缩摘要。
 */
public record TopicSegment(
        String topic,
        String status,
        String summary
) {
}
