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
}
