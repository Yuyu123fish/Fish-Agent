package com.yuyu.fishagent.memory.shortterm;

/**
 * 关键原文片段：压缩时不改写，保留原始措辞作为重要约束和决策的保底。
 */
public record KeyExcerpt(
        int turnIndex,
        String role,
        String content,
        String reason
) {
}
