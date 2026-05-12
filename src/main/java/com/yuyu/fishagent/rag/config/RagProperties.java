package com.yuyu.fishagent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 召回相关配置，对应 {@code fish.rag.*}。
 * <p>与 {@link MemoryProperties} 中的长期记忆索引、向量维度等配合使用；本类只描述「检索编排」侧参数。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.rag")
public class RagProperties {

    /** 总开关：false 时不访问 ES，主对话不受影响。 */
    private boolean enabled = false;

    /**
     * 是否启用「查询重写」链路（Identity / ChatModel）；false 时直接用用户原文进入多查询扩展与 ES。
     * <p>多查询扩展无单独开关，在 {@code enabled=true} 时始终执行。</p>
     */
    private boolean rewriteEnabled = false;

    /** 仅在 {@link #rewriteEnabled} 为 true 时生效；见 {@link RewriteProvider}。 */
    private RewriteProvider rewriteProvider = RewriteProvider.NONE;

    /** 子查询条数、向量腿等（ES 召回为全索引，不按会话过滤）。 */
    private Recall recall = new Recall();

    /** 写入系统消息时的截断策略。 */
    private Render render = new Render();

    /** CHAT_MODEL 模式下的采样温度（若底层实现忽略则仅依赖 Prompt）。 */
    private double rewriteTemperature = 0.1;

    /** CHAT_MODEL 模式下的输出 token 上限，抑制长篇「作答」式输出。 */
    private int rewriteMaxTokens = 256;

    /** 接受的重写字符串最大长度；超出视为失败并回退规范化原文。 */
    private int rewriteMaxChars = 512;

    /**
     * 查询重写来源，与具体模型厂商解耦；由 {@code fish.rag.rewrite-provider} 绑定。
     */
    public enum RewriteProvider {
        /** 不调用大模型，只做空白规范化。 */
        NONE,
        /** 使用容器内 {@code @Primary} {@link org.springframework.ai.chat.model.ChatModel}，且仅允许 JSON 改写。 */
        CHAT_MODEL
    }

    @Data
    public static class Recall {

        /** 含「整句一路」在内的子查询最大条数。 */
        private int maxSubQueries = 12;

        /** 分词后片段最短字符数，过滤过短噪声。 */
        private int minTokenChars = 1;

        /** 每个子查询在 ES 侧的 size / k。 */
        private int perSubquerySize = 5;

        /** 是否对用于检索的句子再做一条 kNN（需 EmbeddingModel）。 */
        private boolean vectorLegEnabled = true;

        /** kNN 的 num_candidates，建议 ≥ perSubquerySize。 */
        private int knnNumCandidates = 80;
    }

    @Data
    public static class Render {

        /** 最终注入系统消息的事实条数上限。 */
        private int maxInjectedFacts = 8;

        /** 注入块总字符上限（含提示头）。 */
        private int maxInjectedChars = 4000;
    }
}
