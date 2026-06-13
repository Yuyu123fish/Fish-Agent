package com.yuyu.fishagent.eval;

import java.util.List;

/**
 * 离线 golden set 数据结构。
 *
 * <p>MVP 评测不跑在线 ES，而是对给定候选池做排序评估，便于稳定量化 fusion/rerank/provenance 的效果。</p>
 */
public final class GoldenSet {

    private GoldenSet() {
    }

    public record Case(String id, String query, List<Candidate> candidates) {
    }

    public record Candidate(String id, String content, double score, String sourceLabel,
                            double authority, Long createdAt, int relevance) {
    }
}
