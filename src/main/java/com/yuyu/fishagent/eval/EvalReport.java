package com.yuyu.fishagent.eval;

/**
 * 单次离线评测报告。
 */
public record EvalReport(int caseCount,
                         RetrievalMetrics.Result baseline,
                         RetrievalMetrics.Result provenance) {
}
