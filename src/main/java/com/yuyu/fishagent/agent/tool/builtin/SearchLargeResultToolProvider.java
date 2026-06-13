package com.yuyu.fishagent.agent.tool.builtin;

import com.yuyu.fishagent.agent.tool.AgentToolProvider;
import com.yuyu.fishagent.agent.tool.result.LargeResultScratchStore;
import com.yuyu.fishagent.common.trace.TraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 单轮大工具结果检索工具。
 *
 * <p>当某个工具返回过大的日志/文件/网页时，注册中心会把完整结果放入 scratch store，
 * 并在上下文里提示模型用本工具按关键词取回相关片段。</p>
 */
@Component
@RequiredArgsConstructor
public class SearchLargeResultToolProvider implements AgentToolProvider {

    private final LargeResultScratchStore scratchStore;

    public record Input(String query) {
    }

    @Override
    public String name() {
        return "search_large_result";
    }

    @Override
    public ToolCallback build() {
        Function<Input, String> fn = input -> {
            String turnId = TraceContext.currentTurnId();
            String query = input == null ? "" : input.query();
            return scratchStore.search(turnId, query).render();
        };
        return FunctionToolCallback.builder(name(), fn)
                .description("检索本轮对话中被放入 scratch 的超大工具结果。query 写关键词、错误码、路径或日志特征。")
                .inputType(Input.class)
                .build();
    }
}
