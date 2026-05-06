package com.yuyu.fishagent.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 所有 Agent 的抽象基类，封装共用的核心能力：
 * <ol>
 *   <li>持有 {@link ChatModel} 与最大迭代次数；</li>
 *   <li>统一构造 {@link ReactAgent}（可被子类按需覆写）；</li>
 *   <li>维护 {@link AgentStatus} 状态机，配合 {@code maxIterations} 双重防止死循环。</li>
 * </ol>
 *
 * <p>子类只负责<strong>组装工具集</strong>与<strong>定制系统人设</strong>，
 * 真正的对话调度委托给 {@code ReactAgent}。
 */
@Slf4j
@Getter
public abstract class BaseAgent {

    protected final ChatModel chatModel;

    protected final int maxIterations;

    /**
     * Agent 名称，用于日志与可观测。
     */
    protected final String name;

    /**
     * 当前状态（线程安全）。
     */
    private final AtomicReference<AgentStatus> status = new AtomicReference<>(AgentStatus.IDLE);

    protected BaseAgent(String name, ChatModel chatModel, int maxIterations) {
        this.name = name;
        this.chatModel = chatModel;
        this.maxIterations = maxIterations;
    }

    /**
     * 通用 ReactAgent 构建工厂。子类拿到这把"半成品 builder"后只需补充工具/人设即可。
     *
     * @param tools 工具回调列表（可能为空）
     * @param systemPrompt 系统提示词，作为 SystemMessage 注入对话头部
     * @return 已构建好的 {@link ReactAgent}
     */
    protected ReactAgent buildReactAgent(List<ToolCallback> tools, String systemPrompt) {
        try {
            // 第一道防线：CompileConfig.recursionLimit 限制 graph 节点切换次数（硬上限，触达将抛异常）
            CompileConfig compileConfig = CompileConfig.builder()
                    .saverConfig(SaverConfig.builder().build())
                    .recursionLimit(Math.max(maxIterations * 4, 20))
                    .build();
            // 第二道防线：ModelCallLimitHook 优雅终止——达到 maxIterations 次模型调用后追加一条
            // AssistantMessage 跳到 end 节点，模型不会再被调用，避免 Token 浪费
            ModelCallLimitHook limitHook = ModelCallLimitHook.builder()
                    .runLimit(maxIterations)
                    .exitBehavior(ModelCallLimitHook.ExitBehavior.END)
                    .build();
            return ReactAgent.builder()
                    .name(this.name)
                    .model(this.chatModel)
                    .systemPrompt(systemPrompt)
                    .tools(tools == null ? List.of() : tools)
                    .hooks(limitHook)
                    .compileConfig(compileConfig)
                    .build();
        } catch (Exception e) {
            log.error("[Agent:{}] 构建 ReactAgent 失败", this.name, e);
            throw new IllegalStateException("Failed to build ReactAgent: " + e.getMessage(), e);
        }
    }

    public AgentStatus getStatus() {
        return status.get();
    }

    /**
     * 状态迁移；外层 Service 在 stream 开始/结束/异常时驱动。
     */
    public void transitionTo(AgentStatus next) {
        AgentStatus prev = status.getAndSet(next);
        log.debug("[Agent:{}] status: {} -> {}", name, prev, next);
    }
}
