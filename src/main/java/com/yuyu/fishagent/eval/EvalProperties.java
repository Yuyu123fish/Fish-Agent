package com.yuyu.fishagent.eval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Eval 配置，对应 {@code fish.eval.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.eval")
public class EvalProperties {

    /** Golden set 默认路径；MVP runner 可由测试或脚本显式加载。 */
    private String goldenSetPath = "classpath:eval/golden-rag.json";

    /** LLM-as-judge 复用 memoryChatModel。 */
    private String judgeModel = "memoryChatModel";

    /** 摘要 eval 配置：CI 默认只跑纯函数，live runner 手动启用。 */
    private Summary summary = new Summary();

    @Data
    public static class Summary {

        /** 是否启用接模型的摘要 A/B live eval。 */
        private boolean liveEnabled = false;

        /** 摘要 golden set 默认路径。 */
        private String goldenSetPath = "classpath:eval/golden-summary.json";

        /** 对账早期信息保留率相对全量基线的阶段 3 决策线。 */
        private double earlyInfoRetentionRatioThreshold = 0.9;
    }
}
