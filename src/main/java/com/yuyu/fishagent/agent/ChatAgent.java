package com.yuyu.fishagent.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.yuyu.fishagent.agent.tool.ToolRegistry;
import com.yuyu.fishagent.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 助手型 Agent（ReAct 模式）。
 * <p>
 * 借助 {@link ReactAgent} 提供"思考-行动-观察"循环；通过 {@code maxIterations} 与
 * {@link AgentStatus} 联合防止死循环。
 */
@Slf4j
@Component
public class ChatAgent extends BaseAgent {

    private final ToolRegistry toolRegistry;

    private final AgentProperties properties;

    private ReactAgent reactAgent;

    public ChatAgent(ChatModel chatModel, ToolRegistry toolRegistry, AgentProperties properties) {
        super("fish-assistant", chatModel, properties.getMaxIterations());
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        // 人设与短期摘要、RAG 等系统段由 ChatService 合并为单条 SystemMessage 注入，此处留空，
        // 避免与消息列表叠加出多条 SystemMessage 触发 Alibaba AgentLlmNode 的英文 WARN。
        this.reactAgent = buildReactAgent(toolRegistry.allCallbacks(), "");
        log.info("[ChatAgent] 初始化完成，工具数量={}, maxIterations={}",
                toolRegistry.size(), properties.getMaxIterations());
    }

    /**
     * 流式推理：返回底层 graph 的 {@link NodeOutput} 序列，由上层 Service 过滤 chunk 并推 SSE。
     *
     * @param messages 全量上下文消息（含 system / 历史 / 当前 user）
     * @param threadId 用于 graph 内部追踪与可观测的会话 ID
     */
    public Flux<NodeOutput> stream(List<Message> messages, String threadId) {
        try {
            transitionTo(AgentStatus.RUNNING);
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(threadId)
                    .build();
            return reactAgent.stream(messages, config)
                    .doOnComplete(() -> transitionTo(AgentStatus.FINISHED))
                    .doOnError(e -> {
                        log.warn("[ChatAgent] stream 异常: {}", e.getMessage());
                        transitionTo(AgentStatus.ERROR);
                    })
                    .doOnCancel(() -> transitionTo(AgentStatus.IDLE));
        } catch (Exception e) {
            transitionTo(AgentStatus.ERROR);
            return Flux.error(e);
        }
    }
}
