package com.yuyu.fishagent.config.llm;

import java.util.Locale;

/**
 * 对话 / 嵌入<strong>业务侧</strong>提供方枚举（嵌入路由复用本枚举类型，见 {@link FishLlmEmbeddingProperties}）。
 * <p>
 * 与 Spring AI 自动配置中的 {@code spring.ai.model.chat} 取值对应关系：
 * {@link #DASHSCOPE} → {@code dashscope}，{@link #OLLAMA} → {@code ollama}，{@link #DEEPSEEK} → {@code openai}
 * （DeepSeek 使用 OpenAI-compatible 客户端）。应用代码只依赖 {@link org.springframework.ai.chat.model.ChatModel}。
 * </p>
 * <p>
 * <strong>嵌入：</strong>{@link #DEEPSEEK} 仅用于对话；{@code fish.llm.embedding.provider} 请勿设为 DEEPSEEK，
 * 嵌入请继续使用 {@link #DASHSCOPE} 或 {@link #OLLAMA}。
 * </p>
 */
public enum FishLlmChatProvider {

    /**
     * 使用阿里云 DashScope（通义等）HTTP API，需配置 {@code DASHSCOPE_API_KEY}。
     */
    DASHSCOPE("dashscope"),

    /**
     * 使用本机或局域网 Ollama 服务，需可访问 {@code spring.ai.ollama.base-url} 且已拉取对应模型。
     */
    OLLAMA("ollama"),

    /**
     * 使用 DeepSeek（OpenAI-compatible），需配置 {@code DEEPSEEK_API_KEY}、{@code spring.ai.openai.base-url}。
     * <p>仅对话链路支持；嵌入不支持此值。</p>
     */
    DEEPSEEK("openai");

    private final String springAiModelChatValue;

    FishLlmChatProvider(String springAiModelChatValue) {
        this.springAiModelChatValue = springAiModelChatValue;
    }

    /**
     * 返回写入 {@code spring.ai.model.chat} 的合法字面量，供自动配置选择 Chat 实现。
     *
     * @return 小写标识，与 Alibaba / Spring AI 条件注解 {@code havingValue} 一致
     */
    public String toSpringAiModelChatValue() {
        return springAiModelChatValue;
    }

    /**
     * 从配置文件或环境变量中的字符串解析枚举（大小写不敏感，支持 {@code DASHSCOPE}、{@code dashscope} 等）。
     *
     * @param raw 原始字符串，可为 {@code null} 或空白
     * @return 解析成功返回对应枚举；为空时默认 {@link #DASHSCOPE}
     * @throws IllegalArgumentException 无法识别时抛出，避免启动后静默连错模型
     */
    public static FishLlmChatProvider parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DASHSCOPE;
        }
        String n = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (n) {
            case "DASHSCOPE" -> DASHSCOPE;
            case "OLLAMA" -> OLLAMA;
            case "DEEPSEEK" -> DEEPSEEK;
            default -> throw new IllegalArgumentException(
                    "未知的 fish.llm.chat-provider / embedding.provider: '" + raw
                            + "'，请使用 DASHSCOPE、OLLAMA 或 DEEPSEEK（嵌入请勿使用 DEEPSEEK）");
        };
    }
}
