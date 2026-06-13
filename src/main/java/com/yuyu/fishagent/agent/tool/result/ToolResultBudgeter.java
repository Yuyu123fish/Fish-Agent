package com.yuyu.fishagent.agent.tool.result;

import com.yuyu.fishagent.common.util.TokenEstimator;
import org.springframework.stereotype.Component;

/**
 * 单工具结果预算器。
 *
 * <p>截断策略采用 head + tail：头部通常包含 JSON schema、状态码或文件开头结构，尾部常包含最新日志、
 * 错误堆栈结尾或最终结论；中间用显式 marker 告诉模型该结果经过治理。</p>
 */
@Component
public class ToolResultBudgeter {

    public BudgetedResult fit(String text, int budgetTokens, String disposition) {
        if (text == null) {
            return new BudgetedResult(null, "unchanged", 0);
        }
        int safeBudget = Math.max(1, budgetTokens);
        int originalTokens = TokenEstimator.estimate(text);
        if (originalTokens <= safeBudget) {
            return new BudgetedResult(text, "unchanged", originalTokens);
        }

        int maxChars = Math.max(80, initialCharBudget(safeBudget, text));
        String marker = "\n\n[工具结果已" + dispositionLabel(disposition) + "：原始约 " + originalTokens
                + " tokens，按单结果预算保留开头与结尾，中间省略。]\n\n";
        if (maxChars <= marker.length() + 20) {
            return new BudgetedResult(text.substring(0, Math.min(text.length(), maxChars)), disposition, originalTokens);
        }

        int bodyChars = maxChars - marker.length();
        int headChars = Math.max(20, (int) (bodyChars * 0.55));
        int tailChars = Math.max(20, bodyChars - headChars);

        String governed = join(text, headChars, tailChars, marker);
        while (TokenEstimator.estimate(governed) > safeBudget && headChars > 20 && tailChars > 20) {
            headChars = Math.max(20, (int) (headChars * 0.9));
            tailChars = Math.max(20, (int) (tailChars * 0.9));
            governed = join(text, headChars, tailChars, marker);
        }
        return new BudgetedResult(governed, disposition, originalTokens);
    }

    /**
     * 按文本的 CJK/Latin 密度估算首轮字符预算。
     *
     * <p>不能用固定 {@code tokens * 4}：中文在 {@link TokenEstimator} 中约 1.5 字符/token，
     * 固定 Latin 比例会让 6K-16K 中文字符绕过单结果预算。</p>
     */
    private int initialCharBudget(int safeBudget, String text) {
        int sampleChars = Math.min(text.length(), 2_000);
        int sampleTokens = Math.max(1, TokenEstimator.estimate(text.substring(0, sampleChars)));
        double charsPerToken = Math.max(1.0, Math.min(4.0, sampleChars / (double) sampleTokens));
        return (int) Math.floor(safeBudget * charsPerToken);
    }

    private String dispositionLabel(String disposition) {
        return switch (disposition) {
            case "retrieved" -> "检索式注入";
            case "summarized" -> "摘要";
            case "truncated" -> "截断";
            default -> disposition == null ? "治理" : disposition;
        };
    }

    private String join(String text, int headChars, int tailChars, String marker) {
        return text.substring(0, Math.min(headChars, text.length()))
                + marker
                + text.substring(Math.max(0, text.length() - tailChars));
    }

    public record BudgetedResult(String content, String disposition, int originalTokens) {
    }
}
