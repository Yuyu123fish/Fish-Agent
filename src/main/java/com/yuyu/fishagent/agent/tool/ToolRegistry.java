package com.yuyu.fishagent.agent.tool;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自动发现并注册所有 {@link AgentToolProvider} Bean，对外提供统一的工具列表。
 * <p>
 * 设计目标：
 * <ul>
 *   <li>新增工具零侵入——只需添加一个 {@code @Component} + 实现接口。</li>
 *   <li>启动期一次性构建 {@link ToolCallback}，避免请求路径中重复初始化。</li>
 *   <li>对失败的工具构造采取"隔离失败"策略，单个工具异常不影响其它工具可用性。</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolRegistry {

    private final List<AgentToolProvider> providers;

    private final List<ToolCallback> callbacks = new ArrayList<>();

    public ToolRegistry(List<AgentToolProvider> providers) {
        this.providers = providers == null ? List.of() : providers;
    }

    @PostConstruct
    public void init() {
        for (AgentToolProvider p : providers) {
            if (!p.enabled()) {
                log.info("[ToolRegistry] 跳过未启用工具: {}", p.name());
                continue;
            }
            try {
                ToolCallback original = p.build();
                if (original == null) {
                    log.warn("[ToolRegistry] 工具 {} 构造返回 null，已跳过", p.name());
                    continue;
                }
                // 包一层 debug 日志，每次工具调用时打印工具名与输入摘要
                String toolName = p.name();
                ToolCallback cb = new ToolCallback() {
                    @Override
                    public String call(String toolInput) {
                        String summary = toolInput != null && toolInput.length() > 200
                                ? toolInput.substring(0, 200) + "..."
                                : toolInput;
                        log.debug("[Tool] 调用工具: {}，输入: {}", toolName, summary);
                        try {
                            String result = original.call(toolInput);
                            log.debug("[Tool] 工具 {} 完成，返回长度: {} chars",
                                    toolName, result != null ? result.length() : 0);
                            return result;
                        } catch (Exception e) {
                            log.warn("[Tool] 工具 {} 异常: {}", toolName, e.getMessage());
                            throw e;
                        }
                    }

                    @Override
                    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                        return original.getToolDefinition();
                    }
                };
                callbacks.add(cb);
                log.info("[ToolRegistry] 注册工具成功: {}", p.name());
            } catch (Exception e) {
                log.error("[ToolRegistry] 工具 {} 构造失败，已跳过", p.name(), e);
            }
        }
        log.info("[ToolRegistry] 共注册 {} 个工具", callbacks.size());
    }

    /**
     * 不可变视图，避免外部误改。
     */
    public List<ToolCallback> allCallbacks() {
        return Collections.unmodifiableList(callbacks);
    }

    public int size() {
        return callbacks.size();
    }
}
