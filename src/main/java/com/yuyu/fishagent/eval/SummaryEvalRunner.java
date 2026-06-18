package com.yuyu.fishagent.eval;

import com.yuyu.fishagent.memory.MemoryCompressionService;
import com.yuyu.fishagent.memory.compress.MemoryPromptBuilder;
import com.yuyu.fishagent.memory.compress.MemoryResponseParser;
import com.yuyu.fishagent.memory.compress.MemoryResponseParser.StructuredCompressionResult;
import com.yuyu.fishagent.memory.shortterm.StructuredSummary;
import com.yuyu.fishagent.memory.shortterm.UserSignals;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 摘要 live eval 编排器。
 *
 * <p>该 runner 会真实调用记忆模型，对同一 golden case 分别运行全量校准基线和对账校准，
 * 再用 {@link SummaryEvalMetrics} 计算 A/B 指标。它不应进入普通 CI。</p>
 */
@Component
@ConditionalOnProperty(prefix = "fish.eval.summary", name = "live-enabled", havingValue = "true")
@RequiredArgsConstructor
public class SummaryEvalRunner {

    @Qualifier("memoryChatModel")
    private final ChatModel memoryChatModel;
    private final MemoryPromptBuilder promptBuilder;
    private final MemoryResponseParser responseParser;

    public SummaryEvalReport run(SummaryGoldenSet goldenSet, double earlyRetentionRatioThreshold) {
        List<SummaryGoldenSet.Case> cases = goldenSet == null ? List.of() : goldenSet.cases();
        if (cases.isEmpty()) {
            SummaryEvalMetrics.Result zero = new SummaryEvalMetrics.Result(0.0, 0.0, 0.0, 0.0);
            return new SummaryEvalReport(0, zero, zero, SummaryEvalReport.DecisionStatus.INSUFFICIENT_BASELINE, false, List.of());
        }

        List<SummaryEvalReport.CaseResult> results = new ArrayList<>();
        SummaryEvalMetrics.Result fullSum = new SummaryEvalMetrics.Result(0.0, 0.0, 0.0, 0.0);
        SummaryEvalMetrics.Result reconciliationSum = new SummaryEvalMetrics.Result(0.0, 0.0, 0.0, 0.0);

        for (SummaryGoldenSet.Case item : cases) {
            StructuredCompressionResult fullResult = runPrompt(promptBuilder.buildCalibration(item.session(), item.windowSize()));
            StructuredSummary seedSummary = buildSeedSummary(item);
            Prompt reconciliationPrompt = promptBuilder.buildReconciliation(
                    seedSummary,
                    MemoryCompressionService.recentMessages(item.session(), item.windowSize()),
                    item.windowSize());
            StructuredCompressionResult reconciliationResult = runPrompt(reconciliationPrompt);

            SummaryEvalMetrics.Result fullMetrics = SummaryEvalMetrics.eval(fullResult, item);
            SummaryEvalMetrics.Result reconciliationMetrics = SummaryEvalMetrics.eval(reconciliationResult, item);
            results.add(new SummaryEvalReport.CaseResult(item.id(), fullMetrics, reconciliationMetrics));
            fullSum = plus(fullSum, fullMetrics);
            reconciliationSum = plus(reconciliationSum, reconciliationMetrics);
        }

        int count = cases.size();
        SummaryEvalMetrics.Result fullAverage = divide(fullSum, count);
        SummaryEvalMetrics.Result reconciliationAverage = divide(reconciliationSum, count);
        SummaryEvalReport.DecisionStatus decisionStatus = decisionStatus(
                fullAverage,
                reconciliationAverage,
                earlyRetentionRatioThreshold
        );
        return new SummaryEvalReport(
                count,
                fullAverage,
                reconciliationAverage,
                decisionStatus,
                decisionStatus == SummaryEvalReport.DecisionStatus.PASSED,
                List.copyOf(results)
        );
    }

    private StructuredCompressionResult runPrompt(Prompt prompt) {
        ChatResponse response = memoryChatModel.call(prompt);
        String output = response.getResult().getOutput().getText();
        return responseParser.parseStructured(output);
    }

    /**
     * 为对账版构造“旧摘要”。
     *
     * <p>真实主链路的旧摘要来自多轮增量压缩累积，而不是窗口外历史的一次全量校准。
     * 这里按 windowSize 对 prefix 分批调用 {@code buildIncremental}，让 live eval 的 seed
     * 更接近真实会话中的漂移起点。</p>
     */
    private StructuredSummary buildSeedSummary(SummaryGoldenSet.Case item) {
        int prefixEnd = Math.max(0, item.session().size() - item.windowSize());
        if (prefixEnd == 0) {
            return emptySummary();
        }
        StructuredSummary summary = emptySummary();
        int batchSize = Math.max(1, item.windowSize());
        for (int from = 0; from < prefixEnd; from += batchSize) {
            int to = Math.min(prefixEnd, from + batchSize);
            summary = runPrompt(promptBuilder.buildIncremental(
                    summary,
                    item.session().subList(from, to),
                    item.windowSize()
            )).summary();
        }
        return summary;
    }

    private static StructuredSummary emptySummary() {
        return new StructuredSummary(List.of(), Map.of(), List.of(), new UserSignals("", "", List.of()));
    }

    private static SummaryEvalReport.DecisionStatus decisionStatus(SummaryEvalMetrics.Result fullAverage,
                                                                   SummaryEvalMetrics.Result reconciliationAverage,
                                                                   double earlyRetentionRatioThreshold) {
        if (fullAverage.structValid() <= 0.0 || fullAverage.earlyInfoRetention() <= 0.0) {
            return SummaryEvalReport.DecisionStatus.INSUFFICIENT_BASELINE;
        }
        return reconciliationAverage.earlyInfoRetention()
                >= fullAverage.earlyInfoRetention() * earlyRetentionRatioThreshold
                ? SummaryEvalReport.DecisionStatus.PASSED
                : SummaryEvalReport.DecisionStatus.FAILED;
    }

    private static SummaryEvalMetrics.Result plus(SummaryEvalMetrics.Result left, SummaryEvalMetrics.Result right) {
        return new SummaryEvalMetrics.Result(
                left.keyEntityRecall() + right.keyEntityRecall(),
                left.topicStatusAccuracy() + right.topicStatusAccuracy(),
                left.earlyInfoRetention() + right.earlyInfoRetention(),
                left.structValid() + right.structValid()
        );
    }

    private static SummaryEvalMetrics.Result divide(SummaryEvalMetrics.Result value, int count) {
        return new SummaryEvalMetrics.Result(
                value.keyEntityRecall() / count,
                value.topicStatusAccuracy() / count,
                value.earlyInfoRetention() / count,
                value.structValid() / count
        );
    }
}
