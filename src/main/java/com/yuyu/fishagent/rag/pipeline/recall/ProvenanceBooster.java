package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.rag.config.RagProperties;

import java.util.Comparator;
import java.util.List;

/**
 * 来源可信度与时效性加权器。
 *
 * <p>它位于 rerank 之后、渲染之前，只调整最终候选的排序分，不改变召回内容。这样权威度/新鲜度作为轻量偏置，
 * 不会掩盖 reranker 对语义相关性的主判断。</p>
 */
public class ProvenanceBooster {

    private static final long DAY_MILLIS = 86_400_000L;

    private final RagProperties properties;

    public ProvenanceBooster(RagProperties properties) {
        this.properties = properties;
    }

    public List<RagRecall.RecallHit> boost(List<RagRecall.RecallHit> hits, long nowMillis) {
        RagProperties.Provenance cfg = properties.getProvenance();
        if (hits == null || hits.isEmpty() || !cfg.isEnabled()) {
            return hits == null ? List.of() : hits;
        }
        return hits.stream()
                .map(hit -> hit.withScore(boostedScore(hit, nowMillis, cfg)))
                .sorted(Comparator.comparingDouble(RagRecall.RecallHit::score).reversed())
                .toList();
    }

    private static double boostedScore(RagRecall.RecallHit hit, long nowMillis, RagProperties.Provenance cfg) {
        double authority = clamp(hit.authority() == null ? defaultAuthority(hit.effectiveSourceLabel()) : hit.authority());
        double recency = recencyNorm(hit.createdAt(), nowMillis, cfg.getRecencyHalfLifeDays());
        return hit.score() * (1.0 + cfg.getAuthorityAlpha() * authority + cfg.getRecencyBeta() * recency);
    }

    private static double defaultAuthority(String label) {
        return SourceAuthority.defaultAuthority(label);
    }

    private static double recencyNorm(Long createdAt, long nowMillis, int halfLifeDays) {
        if (createdAt == null || createdAt <= 0 || createdAt > nowMillis) {
            return 0.0;
        }
        double ageDays = (nowMillis - createdAt) / (double) DAY_MILLIS;
        double halfLife = Math.max(1, halfLifeDays);
        return Math.exp(-ageDays / halfLife);
    }

    private static double clamp(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
