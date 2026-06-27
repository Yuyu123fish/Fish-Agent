package com.yuyu.fishagent.llm.config;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 收口「当前活跃模型名 + 有效上下文窗口」的解析，作为全应用唯一真相源。
 *
 * <p>历史问题：模型名→窗口的映射（{@code fish.llm.model-context-overrides}）与「当前用的是哪个模型」
 * 的解析曾散落在 ChatService 私有方法里，DeepSeek V3→V4 迁移时改了模型名却忘了同步 override
 * （65536 这个 V3 的 64K 数被一路带到 V4 的 1M 模型）。本类把两段逻辑合并收口，供
 * {@code ContextBudgetAllocator}（对话预算）与 {@code ToolResultGovernor}（工具结果预算）共用，
 * 避免同一份「窗口有多大」的事实被多处各自解析而漂移。</p>
 *
 * <p>激活模型在运行期由配置决定、不会热切换，故按调用即时解析即可（一次 Map 查找 + 一次 Environment
 * 读取，开销可忽略），无需缓存。</p>
 */
@Component
@RequiredArgsConstructor
public class ActiveChatModelContext {

    private final FishLlmProperties fishLlmProperties;
    private final Environment environment;

    /**
     * 当前 provider 实际生效的对话模型名，用于匹配 {@code fish.llm.model-context-overrides}。
     *
     * @return 模型名；对应属性未配置时为 {@code null}（由调用方按「未知模型→默认窗口」处理）
     */
    public String activeModelName() {
        return switch (fishLlmProperties.getChatProvider()) {
            case DEEPSEEK -> environment.getProperty("spring.ai.openai.chat.options.model");
            case OLLAMA -> environment.getProperty("spring.ai.ollama.chat.options.model");
            case DASHSCOPE -> environment.getProperty("spring.ai.dashscope.chat.options.model");
        };
    }

    /**
     * 当前模型的有效上下文窗口（token）：命中 override 用 override，否则回退全局默认。
     */
    public int effectiveContextWindow() {
        return fishLlmProperties.getEffectiveContextWindowTokens(activeModelName());
    }
}
