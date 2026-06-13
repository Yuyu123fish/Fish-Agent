package com.yuyu.fishagent.memory.longterm;

/**
 * 与候选长期事实相似的历史事实。
 *
 * <p>record 只承载判定所需最小信息，避免冲突治理层依赖 ES SearchHit 等基础设施类型。</p>
 */
public record SimilarFact(String id, String content, long createdAt, double score) {
}
