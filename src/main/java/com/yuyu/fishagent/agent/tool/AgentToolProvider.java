package com.yuyu.fishagent.agent.tool;

import org.springframework.ai.tool.ToolCallback;

/**
 * Agent 工具的统一 SPI。
 * <p>
 * 任意一个 Bean 实现该接口并被 Spring 容器装配，即会被 {@link ToolRegistry} 自动收集，
 * 进而暴露给 {@code ReactAgent}。新增工具时<strong>无需改动核心代码</strong>，
 * 只需新建一个 {@code @Component} 类即可。
 *
 * <p>典型用法：实现 {@link #build()} 时返回一个 {@code FunctionToolCallback}。
 */
public interface AgentToolProvider {

    /**
     * 工具的稳定唯一标识，建议用 {@code snake_case}，与最终给 LLM 的工具名一致。
     */
    String name();

    /**
     * 构造该工具的 {@link ToolCallback} 实例（可能涉及 RestClient/SDK 初始化）。
     * <p>实现请保证返回值幂等：{@link ToolRegistry} 会在启动期调用一次。
     */
    ToolCallback build();

    /**
     * 是否启用。默认启用；外部工具一般通过 {@code @ConditionalOnProperty} 控制 Bean 是否注册，
     * 这里留出第二道兜底开关用于运行时动态屏蔽。
     */
    default boolean enabled() {
        return true;
    }
}
