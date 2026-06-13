package com.yuyu.fishagent.eval;

import com.yuyu.fishagent.rag.config.RagProperties;
import com.yuyu.fishagent.rag.pipeline.recall.ProvenanceBooster;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 离线评测 runner。
 *
 * <p>输入 golden cases，分别计算 baseline（按候选原始 score）与 v6.0 provenance 排序后的指标。
 * 后续 live-eval 可以替换候选来源为真实召回结果，但指标聚合仍复用这里。</p>
 */
public class EvalRunner {

    private final ProvenanceBooster booster;

    public EvalRunner() {
        this(defaultProperties());
    }

    public EvalRunner(RagProperties ragProperties) {
        this.booster = new ProvenanceBooster(ragProperties);
    }

    public EvalReport run(List<GoldenSet.Case> cases, int k) {
        if (cases == null || cases.isEmpty()) {
            RetrievalMetrics.Result zero = new RetrievalMetrics.Result(0.0, 0.0, 0.0);
            return new EvalReport(0, zero, zero);
        }
        double baselinePrecision = 0.0;
        double baselineMrr = 0.0;
        double baselineNdcg = 0.0;
        double provenancePrecision = 0.0;
        double provenanceMrr = 0.0;
        double provenanceNdcg = 0.0;
        long now = System.currentTimeMillis();

        for (GoldenSet.Case item : cases) {
            Map<String, Integer> relevance = relevanceMap(item.candidates());
            RetrievalMetrics.Result baseline = RetrievalMetrics.evaluate(
                    item.candidates().stream()
                            .sorted(Comparator.comparingDouble(GoldenSet.Candidate::score).reversed())
                            .map(GoldenSet.Candidate::id)
                            .toList(),
                    relevance,
                    k);
            RetrievalMetrics.Result provenance = RetrievalMetrics.evaluate(
                    booster.boost(toHits(item.candidates()), now).stream()
                            .map(RagRecall.RecallHit::id)
                            .toList(),
                    relevance,
                    k);
            baselinePrecision += baseline.precisionAtK();
            baselineMrr += baseline.mrr();
            baselineNdcg += baseline.ndcgAtK();
            provenancePrecision += provenance.precisionAtK();
            provenanceMrr += provenance.mrr();
            provenanceNdcg += provenance.ndcgAtK();
        }

        int n = cases.size();
        return new EvalReport(
                n,
                new RetrievalMetrics.Result(baselinePrecision / n, baselineMrr / n, baselineNdcg / n),
                new RetrievalMetrics.Result(provenancePrecision / n, provenanceMrr / n, provenanceNdcg / n));
    }

    private static List<RagRecall.RecallHit> toHits(List<GoldenSet.Candidate> candidates) {
        return candidates.stream()
                .map(c -> new RagRecall.RecallHit(c.id(), c.content(), c.score(), RagRecall.RecallSource.TEXT,
                        c.sourceLabel(), c.authority(), c.createdAt(), null, null))
                .toList();
    }

    private static Map<String, Integer> relevanceMap(List<GoldenSet.Candidate> candidates) {
        Map<String, Integer> relevance = new LinkedHashMap<>();
        for (GoldenSet.Candidate candidate : candidates) {
            relevance.put(candidate.id(), candidate.relevance());
        }
        return relevance;
    }

    private static RagProperties defaultProperties() {
        RagProperties properties = new RagProperties();
        properties.getProvenance().setEnabled(true);
        return properties;
    }
}
