package com.yuyu.fishagent.agent.tool;

import com.yuyu.fishagent.agent.config.ToolProperties;
import com.yuyu.fishagent.agent.tool.result.ToolResultGovernor;
import com.yuyu.fishagent.common.trace.TraceContext;
import com.yuyu.fishagent.common.util.TextTruncator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ToolProperties toolProperties;
    private final ToolResultGovernor toolResultGovernor;

    private final List<ToolCallback> callbacks = new ArrayList<>();
    private final List<RegisteredTool> registeredTools = new ArrayList<>();

    @Autowired
    public ToolRegistry(List<AgentToolProvider> providers,
                        ToolProperties toolProperties,
                        ToolResultGovernor toolResultGovernor) {
        this.providers = providers == null ? List.of() : providers;
        this.toolProperties = toolProperties;
        this.toolResultGovernor = toolResultGovernor;
    }

    /**
     * 测试兼容构造器：未提供 v6.2 governor 时保留旧字符上限治理。
     */
    public ToolRegistry(List<AgentToolProvider> providers, ToolProperties toolProperties) {
        this.providers = providers == null ? List.of() : providers;
        this.toolProperties = toolProperties;
        this.toolResultGovernor = null;
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
                String toolName = p.name();
                registeredTools.add(new RegisteredTool(toolName, original));
                callbacks.add(wrap(toolName, original, null));
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

    /**
     * 为指定 turn 动态创建工具回调，确保工具结果治理、scratch store 与 TurnTrace 都能绑定本轮 turnId。
     */
    public List<ToolCallback> allCallbacks(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return allCallbacks();
        }
        return registeredTools.stream()
                .map(tool -> wrap(tool.name(), tool.callback(), turnId))
                .toList();
    }

    public int size() {
        return callbacks.size();
    }

    /**
     * 统一治理工具返回：智能截断长文本，并对较长结果追加 ReAct 使用提示。
     */
    private String governResult(String toolName, String result) {
        if (toolResultGovernor != null) {
            return toolResultGovernor.govern(TraceContext.currentTurnId(), toolName, null, result).content();
        }
        if (result == null) {
            return null;
        }
        int limit = toolProperties.getMaxResultChars(toolName);
        String hint = "\n\n[提示：以上工具返回内容较长，请先提取关键信息再回答用户，避免在回复中大段复述原始内容。]";
        boolean shouldAppendHint = toolProperties.getHintThresholdChars() > 0
                && result.length() > toolProperties.getHintThresholdChars();
        String governed = result;
        if (limit > 0 && governed.length() > limit) {
            int bodyLimit = shouldAppendHint ? Math.max(1, limit - hint.length()) : limit;
            governed = TextTruncator.truncateWithin(governed, bodyLimit);
            log.debug("[Tool] {} 结果已截断 limit={}", toolName, limit);
        }
        if (shouldAppendHint) {
            governed = appendWithinLimit(governed, hint, limit);
        }
        return governed;
    }

    private String appendWithinLimit(String value, String suffix, int limit) {
        if (limit <= 0 || value.length() + suffix.length() <= limit) {
            return value + suffix;
        }
        if (limit <= suffix.length()) {
            return TextTruncator.truncateWithin(value + suffix, limit);
        }
        int bodyLimit = Math.max(1, limit - suffix.length());
        return TextTruncator.truncateWithin(value, bodyLimit) + suffix;
    }

    private ToolCallback wrap(String toolName, ToolCallback original, String turnId) {
        return new ToolCallback() {
            @Override
            public String call(String toolInput) {
                String summary = toolInput != null && toolInput.length() > 200
                        ? toolInput.substring(0, 200) + "..."
                        : toolInput;
                log.debug("[Tool] 调用工具: {}，输入: {}", toolName, summary);
                String previousTurnId = TraceContext.currentTurnId();
                if (turnId != null && !turnId.isBlank()) {
                    TraceContext.setTurnId(turnId);
                }
                try {
                    String result = original.call(toolInput);
                    result = toolResultGovernor == null
                            ? governResult(toolName, result)
                            : toolResultGovernor.govern(turnId, toolName, toolInput, result).content();
                    log.debug("[Tool] 工具 {} 完成，返回长度: {} chars",
                            toolName, result != null ? result.length() : 0);
                    return result;
                } catch (Exception e) {
                    log.warn("[Tool] 工具 {} 异常: {}", toolName, e.getMessage());
                    throw e;
                } finally {
                    if (turnId != null && !turnId.isBlank()) {
                        if (previousTurnId == null) {
                            TraceContext.clear();
                        } else {
                            TraceContext.setTurnId(previousTurnId);
                        }
                    }
                }
            }

            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return original.getToolDefinition();
            }
        };
    }

    private record RegisteredTool(String name, ToolCallback callback) {
    }
}
