package com.yuyu.fishagent.rag.pipeline.fusion;

import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）分数融合。
 * <p>它只看各召回批次内部的排名，不直接比较 BM25 与 cosine 原始分数，避免不同尺度的分数互相污染。</p>
 */
public final class RagScoreFusion {

    private RagScoreFusion() {
    }

    /**
     * 对多组召回结果做 RRF 融合，并将融合分写回 {@link RagRecall.RecallHit#score()}。
     *
     * @param batches  多组召回结果，每组会先按原始 score 降序得到组内 rank
     * @param rrfK     RRF 常数 k，越大则头部 rank 差距越平滑
     * @param poolSize 候选池上限
     * @return 按融合分降序排列的候选池
     */
    public static List<RagRecall.RecallHit> fuseByRrf(List<List<RagRecall.RecallHit>> batches,
                                                      int rrfK,
                                                      int poolSize) {
        int k = Math.max(1, rrfK);
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, RagRecall.RecallHit> representatives = new LinkedHashMap<>();

        if (batches != null) {
            for (List<RagRecall.RecallHit> batch : batches) {
                if (batch == null || batch.isEmpty()) {
                    continue;
                }
                List<RagRecall.RecallHit> sorted = batch.stream()
                        .filter(hit -> RagRecall.dedupKey(hit) != null)
                        .sorted(Comparator.comparingDouble(RagRecall.RecallHit::score).reversed())
                        .toList();

                for (int rank = 0; rank < sorted.size(); rank++) {
                    RagRecall.RecallHit hit = sorted.get(rank);
                    String key = RagRecall.dedupKey(hit);
                    fusedScores.merge(key, 1.0 / (k + rank + 1), Double::sum);
                    // 同一命中可能来自多路召回，代表内容保留原始分最高的一条，方便后续渲染。
                    representatives.merge(key, hit, (a, b) -> a.score() >= b.score() ? a : b);
                }
            }
        }

        List<RagRecall.RecallHit> out = new ArrayList<>(fusedScores.size());
        for (Map.Entry<String, Double> entry : fusedScores.entrySet()) {
            RagRecall.RecallHit hit = representatives.get(entry.getKey());
            out.add(new RagRecall.RecallHit(hit.id(), hit.content(), entry.getValue(), hit.source()));
        }
        return out.stream()
                .sorted(Comparator.comparingDouble(RagRecall.RecallHit::score).reversed())
                .limit(Math.max(0, poolSize))
                .toList();
    }
}
