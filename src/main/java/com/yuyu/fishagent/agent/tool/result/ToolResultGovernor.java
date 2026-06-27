package com.yuyu.fishagent.agent.tool.result;

import com.yuyu.fishagent.common.metrics.ChatMetrics;
import com.yuyu.fishagent.common.trace.TraceCollector;
import com.yuyu.fishagent.common.util.TokenEstimator;
import com.yuyu.fishagent.llm.config.ActiveChatModelContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具结果治理总入口。
 *
 * <p>路由顺序：C scratch（真正巨量）→ B 摘要（中等大）→ A 头尾截断（兜底）。
 * 所有路径最终都受单结果预算约束，保证单个工具结果不会吃爆整轮上下文。</p>
 */
@Component
@RequiredArgsConstructor
public class ToolResultGovernor {

    private final ToolResultProperties properties;
    private final ToolResultBudgeter budgeter;
    private final ToolResultSummarizer summarizer;
    private final LargeResultScratchStore scratchStore;
    private final TraceCollector traceCollector;
    private final ActiveChatModelContext activeChatModelContext;

    public GovernedResult govern(String turnId, String toolName, String toolInput, String result) {
        if (result == null) {
            return new GovernedResult(null, "unchanged", 0);
        }
        int originalTokens = TokenEstimator.estimate(result);
        // 三档阈值随当前模型上下文窗口自缩放（方案 B）；窗口未知(测试/未注入)时退回绝对下限。
        int window = activeChatModelContext == null ? 0 : activeChatModelContext.effectiveContextWindow();
        int budget = properties.effectiveBudgetTokens(toolName, window);
        int scratchThreshold = properties.effectiveScratchThreshold(window);
        int summarizeThreshold = properties.effectiveSummarizeThreshold(window);

        if (properties.isScratchEnabled()
                && originalTokens >= Math.max(budget + 1, scratchThreshold)
                && turnId != null && !turnId.isBlank()
                && !"search_large_result".equals(toolName)) {
            LargeResultScratchStore.StoreResult stored = scratchStore.store(turnId, toolName, result);
            if (stored.stored()) {
                String injected = renderScratchInjection(toolName, originalTokens, stored);
                ToolResultBudgeter.BudgetedResult fitted = budgeter.fit(injected, budget, "retrieved");
                recordDisposition(turnId, toolName, "retrieved", originalTokens, fitted.content());
                return new GovernedResult(fitted.content(), "retrieved", originalTokens);
            }
        }

        if (properties.isSummarizeEnabled()
                && originalTokens >= Math.max(budget + 1, summarizeThreshold)) {
            String summary = summarizer.summarize(toolName, toolInput, result, budget);
            if (summary != null && !summary.isBlank()) {
                ToolResultBudgeter.BudgetedResult fitted = budgeter.fit(summary, budget, "summarized");
                recordDisposition(turnId, toolName, "summarized", originalTokens, fitted.content());
                return new GovernedResult(fitted.content(), "summarized", originalTokens);
            }
        }

        ToolResultBudgeter.BudgetedResult fitted = budgeter.fit(result, budget, "truncated");
        if (!"unchanged".equals(fitted.disposition())) {
            recordDisposition(turnId, toolName, fitted.disposition(), originalTokens, fitted.content());
        }
        return new GovernedResult(fitted.content(), fitted.disposition(), originalTokens);
    }

    public void clearScratch(String turnId) {
        scratchStore.clear(turnId);
    }

    private String renderScratchInjection(String toolName, int originalTokens, LargeResultScratchStore.StoreResult stored) {
        StringBuilder sb = new StringBuilder();
        sb.append("[工具结果过大，已进入本轮 scratch store]\n")
                .append("tool=").append(toolName)
                .append(", scratchId=").append(stored.scratchId())
                .append(", chunks=").append(stored.chunkCount())
                .append(", originalTokens≈").append(originalTokens).append('\n')
                .append("当前只注入少量预览片段。若需要更多细节，请调用 search_large_result，query 写关键词/错误码/路径。\n");
        List<LargeResultScratchStore.ScratchChunk> previews = stored.previewChunks().stream()
                .limit(Math.max(1, properties.getScratchInjectTopK()))
                .toList();
        for (LargeResultScratchStore.ScratchChunk chunk : previews) {
            sb.append("\n[preview #").append(chunk.chunkIndex()).append("]\n")
                    .append(chunk.content()).append('\n');
        }
        return sb.toString().trim();
    }

    private void recordDisposition(String turnId, String toolName, String disposition, int originalTokens, String governed) {
        if (turnId == null || turnId.isBlank()) {
            return;
        }
        String content = "tool=" + toolName
                + ", disposition=" + disposition
                + ", originalTokens≈" + originalTokens
                + ", governedTokens≈" + TokenEstimator.estimate(governed);
        traceCollector.recordNode(turnId, "tool-result-governance", "system", content, 0,
                ChatMetrics.Outcome.SUCCESS.name(), disposition);
    }

    public record GovernedResult(String content, String disposition, int originalTokens) {
    }
}
