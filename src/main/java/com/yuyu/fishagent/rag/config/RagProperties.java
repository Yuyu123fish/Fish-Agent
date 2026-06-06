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

    /** RRF 分数融合（统一 BM25 / cosine 排名）。 */
    private Fusion fusion = new Fusion();

    /** Cross-Encoder 精排。 */
    private Rerank rerank = new Rerank();

    /** LLM 语义查询分解（替代/兜底词级扩展）。 */
    private Expand expand = new Expand();

    /** HyDE 假设性文档嵌入（可选增强，默认关闭）。 */
    private Hyde hyde = new Hyde();

    /** RAG 全链路质量追踪（异步写 ES）。 */
    private Tracing tracing = new Tracing();

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

        /** 每个子查询在 ES 侧的 size / k。v3.4 扩大候选池：5 → 10。 */
        private int perSubquerySize = 10;

        /** 是否对用于检索的句子再做一条 kNN（需 EmbeddingModel）。 */
        private boolean vectorLegEnabled = true;

        /** kNN 的 num_candidates，建议 ≥ perSubquerySize。v3.4 扩大候选池：80 → 120。 */
        private int knnNumCandidates = 120;
    }

    @Data
    public static class Render {

        /** 最终注入系统消息的事实条数上限。 */
        private int maxInjectedFacts = 8;

        /** 注入块总字符上限（含提示头）。 */
        private int maxInjectedChars = 4000;
    }

    @Data
    public static class Fusion {

        /** RRF 融合开关；false 时回退到旧的 max-score 合并。 */
        private boolean enabled = true;

        /** RRF 常数 k，标准值通常为 60。 */
        private int rrfK = 60;

        /** 融合后候选池大小，后续交给 Reranker 精排。 */
        private int candidatePoolSize = 50;
    }

    @Data
    public static class Rerank {

        /** Rerank 开关；false 时直接返回候选池前 topN。 */
        private boolean enabled = true;

        /** DashScope Rerank 模型名。 */
        private String model = "qwen3-rerank";

        /** 精排后保留条数，最终仍受 render.max-injected-facts 约束。 */
        private int topN = 8;

        /** API 调用超时秒数。 */
        private int timeoutSeconds = 5;

        /** 失败时是否降级到融合结果。 */
        private boolean fallbackOnError = true;

        /** DashScope 服务根地址。 */
        private String baseUrl = "https://dashscope.aliyuncs.com";

        /** API Key；默认通过 yml 绑定 DASHSCOPE_API_KEY，为空时自动降级。 */
        private String apiKey = "";
    }

    @Data
    public static class Expand {

        /** 总开关；false 时回退为单条原句检索。 */
        private boolean enabled = true;

        /** 扩展策略：LLM 语义分解 / TOKEN 词级 / IDENTITY 单条原句。 */
        private Strategy strategy = Strategy.LLM;

        /** LLM 分解最多生成的子查询条数。 */
        private int maxQueries = 4;

        /** LLM 采样温度，预留给后续模型 option 扩展。 */
        private double temperature = 0.3;

        /** LLM 调用超时（毫秒），超时降级为单条原句。 */
        private long timeoutMs = 3000;

        /** 子查询可接受的最小字符数。 */
        private int minQueryChars = 5;

        /** 子查询可接受的最大字符数。 */
        private int maxQueryChars = 200;

        public enum Strategy {
            LLM,
            TOKEN,
            IDENTITY
        }
    }

    @Data
    public static class Hyde {

        /** HyDE 开关；默认关闭，开启后仅替换向量腿 embedding 文本。 */
        private boolean enabled = false;

        /** 假设性答案最大输出 token 数，预留给后续模型 option 扩展。 */
        private int maxTokens = 300;

        /** 生成假设性答案的采样温度，预留给后续模型 option 扩展。 */
        private double temperature = 0.5;

        /** LLM 调用超时（毫秒），超时降级回退原 query。 */
        private long timeoutMs = 3000;
    }

    @Data
    public static class Tracing {

        /** 质量追踪开关。 */
        private boolean enabled = true;

        /** 追踪日志 ES 索引名。 */
        private String indexName = "fish-rag-trace";

        /** 是否异步写入，不阻塞对话主流程。 */
        private boolean async = true;

        /** 采样率 0.0~1.0；1.0 表示全量记录。 */
        private double sampleRate = 1.0;
    }
}
