package com.yuyu.fishagent.llm.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.util.Map;

/**
 * 将「业务选择的对话后端」收敛为容器内唯一的 {@link ChatModel} {@code @Primary} Bean，
 * 避免在同时引入 DashScope、Ollama、OpenAI-compatible（DeepSeek）starter 时出现多个 {@link ChatModel} 而无法按类型注入。
 * <p>
 * 实际连哪一侧仍由 {@code spring.ai.model.chat}（及 {@link FishLlmEnvironmentPostProcessor} 对
 * {@code fish.llm.chat-provider} 的补全）决定：未激活的自动配置不会注册对应实现，此处通过
 * {@link ObjectProvider} 在缺失时抛出带说明的异常。
 * </p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class FishChatModelConfiguration {

    private final FishLlmProperties fishLlmProperties;

    private final Environment environment;

    /**
     * 对外暴露的唯一主 {@link ChatModel}，供 {@code ChatAgent}、记忆服务等按类型注入。
     * <p>使用 {@code @Primary} 覆盖多个具体实现 Bean 的注入歧义。</p>
     *
     * @param ollamaChatModel    本地 Ollama 实现（可能因条件注解未注册）
     * @param dashScopeChatModel DashScope 实现（可能因条件注解未注册）
     * @param openAiChatModel    OpenAI 兼容实现（DeepSeek 等，可能因条件注解未注册）
     * @return 与 {@link FishLlmProperties#getChatProvider()} 一致的具体模型
     */
    @Bean
    @Primary
    public ChatModel fishPrimaryChatModel(
            ObjectProvider<OllamaChatModel> ollamaChatModel,
            ObjectProvider<DashScopeChatModel> dashScopeChatModel,
            ObjectProvider<OpenAiChatModel> openAiChatModel) {
        return switch (fishLlmProperties.getChatProvider()) {
            case OLLAMA -> this.requireBean(
                    ollamaChatModel.getIfAvailable(),
                    "OLLAMA",
                    "spring.ai.model.chat=ollama",
                    "OllamaChatModel");
            case DASHSCOPE -> this.requireBean(
                    dashScopeChatModel.getIfAvailable(),
                    "DASHSCOPE",
                    "spring.ai.model.chat=dashscope",
                    "DashScopeChatModel");
            case DEEPSEEK -> this.requireBean(
                    openAiChatModel.getIfAvailable(),
                    "DEEPSEEK",
                    "spring.ai.model.chat=openai",
                    "OpenAiChatModel");
        };
    }

    /**
     * 记忆压缩与长期事实抽取专用 {@link ChatModel}，可与主对话模型分离配置。
     * <ul>
     *   <li>{@code fish.memory.chat.enabled=false}：直接返回主模型，零额外 Bean 行为。</li>
     *   <li>{@code DEEPSEEK}：通过 {@code ObjectProvider<OpenAiApi>} 复用同一 API 客户端，仅覆盖 {@link OpenAiChatOptions}。</li>
     *   <li>其它 provider：当前阶段降级为主模型（后续可扩展 DashScope / Ollama 独立选项）。</li>
     * </ul>
     *
     * @param primaryChatModel   主对话模型（与 {@link #fishPrimaryChatModel} 同一实例）
     * @param memoryProperties        {@code fish.memory.*}，含 {@code chat} 子配置
     * @param openAiApiProvider       OpenAI 兼容 API 客户端（DeepSeek 路径下由自动配置注册）
     * @return 记忆链路使用的 ChatModel
     */
    @Bean("memoryChatModel")
    public ChatModel memoryChatModel(
            @Qualifier("fishPrimaryChatModel") ChatModel primaryChatModel,
            MemoryProperties memoryProperties,
            ObjectProvider<OpenAiApi> openAiApiProvider) {
        MemoryProperties.MemoryChatProperties chatProps = memoryProperties.getChat();
        if (!chatProps.isEnabled()) {
            log.info("[FishLlm] fish.memory.chat.enabled=false，记忆链路降级为主模型");
            return primaryChatModel;
        }
        return switch (fishLlmProperties.getChatProvider()) {
            case DEEPSEEK -> {
                OpenAiApi openAiApi = openAiApiProvider.getIfAvailable();
                if (openAiApi == null) {
                    log.warn("[FishLlm] OpenAiApi 不可用，记忆链路降级为主模型（请检查 spring.ai.openai.* 与 starter）");
                    yield primaryChatModel;
                }
                OpenAiChatOptions opts = OpenAiChatOptions.builder()
                        .model(chatProps.getModel())
                        .temperature(chatProps.getTemperature())
                        // 关闭思考模式：保持非思考行为，规避 V4 thinking 与 tool calling 的已知冲突。
                        // thinking 经 extraBody 平铺到请求体顶层（DeepSeek 要求的位置）。
                        .extraBody(Map.of("thinking", Map.of("type", "disabled")))
                        .build();
                log.info("[FishLlm] 记忆链路独立模型 provider=DEEPSEEK model={} temperature={}",
                        chatProps.getModel(), chatProps.getTemperature());
                yield OpenAiChatModel.builder()
                        .openAiApi(openAiApi)
                        .defaultOptions(opts)
                        .build();
            }
            default -> {
                log.info("[FishLlm] 当前 chat-provider={} 暂不支持独立记忆模型，记忆链路降级为主模型",
                        fishLlmProperties.getChatProvider());
                yield primaryChatModel;
            }
        };
    }

    /**
     * 在所需实现未注册时构造启动失败原因，便于排查配置与条件注解是否一致。
     *
     * @param impl            条件注册得到的实现，可能为 {@code null}
     * @param providerLabel   业务枚举字面量
     * @param frameworkHint   建议检查的框架属性
     * @param beanSimpleName  期望存在的 Bean 简称
     * @return 非 null 的实现
     */
    private ChatModel requireBean(ChatModel impl, String providerLabel, String frameworkHint, String beanSimpleName) {
        if (impl != null) {
            return impl;
        }
        String resolvedChat = environment.getProperty("spring.ai.model.chat", "(未解析)");
        String troubleshooting = chatProviderTroubleshootingHint(providerLabel);
        String msg = String.format(
                "fish.llm.chat-provider=%s 需要 %s Bean，但未注册。当前 Environment 中 spring.ai.model.chat=%s。"
                        + " 期望 %s；若为预期之外的值，请检查 SPRING_AI_MODEL_CHAT / -Dspring.ai.model.chat 是否覆盖，"
                        + "或 FishLlmEnvironmentPostProcessor 属性源顺序。%s",
                providerLabel, beanSimpleName, resolvedChat, frameworkHint, troubleshooting);
        log.error("[FishLlm] {}", msg);
        throw new IllegalStateException(msg);
    }

    /**
     * 按业务提供方给出简短排障指引，避免单一后端（如 Ollama）文案误导其它后端。
     *
     * @param providerLabel {@link FishLlmChatProvider} 名称
     * @return 非空提示句（含前导空格或换行前的分隔）
     */
    private static String chatProviderTroubleshootingHint(String providerLabel) {
        return switch (providerLabel) {
            case "OLLAMA" -> " 请确认 Ollama 已启动、spring.ai.ollama.base-url 可达，且对应 Chat 模型已 pull。";
            case "DASHSCOPE" -> " 请确认已配置 DASHSCOPE_API_KEY，且 dashscope Chat 自动配置未被排除。";
            case "DEEPSEEK" -> " 请确认已配置 DEEPSEEK_API_KEY、spring.ai.openai.base-url（默认 https://api.deepseek.com）。";
            default -> " 请核对所选提供方的 API Key、endpoint 与 starter 依赖。";
        };
    }
}
