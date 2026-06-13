package com.yuyu.fishagent.eval;

/**
 * LLM-as-judge 结果解析与接口定义。
 *
 * <p>MVP 保留可替换接口和稳定 parser；真实模型调用可在 live-eval/profile 中注入，
 * 避免普通单测和 CI 产生外部 LLM 成本。</p>
 */
public interface LlmAnswerJudge {

    JudgeScore judge(String question, String context, String answer);

    static JudgeScore parseScore(double faithfulness, double relevance, double contextUse) {
        return new JudgeScore(clamp(faithfulness), clamp(relevance), clamp(contextUse));
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

    record JudgeScore(double faithfulness, double answerRelevance, double contextUse) {
    }
}
