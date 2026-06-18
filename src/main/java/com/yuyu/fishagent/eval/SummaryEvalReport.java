package com.yuyu.fishagent.eval;

import java.util.List;

/**
 * 摘要 A/B live eval 报告。
 */
public record SummaryEvalReport(int caseCount,
                                SummaryEvalMetrics.Result fullCalibrationAverage,
                                SummaryEvalMetrics.Result reconciliationAverage,
                                DecisionStatus decisionStatus,
                                boolean reconciliationMeetsEarlyRetentionThreshold,
                                List<CaseResult> cases) {

    public enum DecisionStatus {
        PASSED,
        FAILED,
        INSUFFICIENT_BASELINE
    }

    public SummaryEvalReport(int caseCount,
                             SummaryEvalMetrics.Result fullCalibrationAverage,
                             SummaryEvalMetrics.Result reconciliationAverage,
                             boolean reconciliationMeetsEarlyRetentionThreshold,
                             List<CaseResult> cases) {
        this(
                caseCount,
                fullCalibrationAverage,
                reconciliationAverage,
                reconciliationMeetsEarlyRetentionThreshold ? DecisionStatus.PASSED : DecisionStatus.FAILED,
                reconciliationMeetsEarlyRetentionThreshold,
                cases
        );
    }

    public record CaseResult(String id,
                             SummaryEvalMetrics.Result fullCalibration,
                             SummaryEvalMetrics.Result reconciliation) {
    }
}
