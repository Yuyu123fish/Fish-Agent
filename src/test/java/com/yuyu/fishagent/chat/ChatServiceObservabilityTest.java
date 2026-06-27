package com.yuyu.fishagent.chat;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.agent.ChatAgent;
import com.yuyu.fishagent.agent.config.AgentProperties;
import com.yuyu.fishagent.chat.history.ChatMemoryStore;
import com.yuyu.fishagent.common.metrics.ChatMetrics;
import com.yuyu.fishagent.common.ratelimit.RateLimitService;
import com.yuyu.fishagent.llm.config.ActiveChatModelContext;
import com.yuyu.fishagent.llm.config.FishLlmProperties;
import com.yuyu.fishagent.memory.LongTermMemoryIngestionService;
import com.yuyu.fishagent.memory.MemoryCompressionService;
import com.yuyu.fishagent.memory.agentstate.AgentStateStore;
import com.yuyu.fishagent.memory.agentstate.AgentStateUpdater;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.memory.shortterm.ShortTermMemoryService;
import com.yuyu.fishagent.memory.shortterm.ShortTermMemorySnapshot;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceObservabilityTest {

    @Test
    void shouldFinishChatTurnMetricWhenEmitterTimesOutAndCancelsFlux() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicReference<Runnable> timeoutCallback = new AtomicReference<>();

        ChatAgent chatAgent = mock(ChatAgent.class);
        @SuppressWarnings("unchecked")
        Flux<NodeOutput> never = Flux.never();
        when(chatAgent.stream(any(), anyString(), anyString())).thenReturn(never);

        ShortTermMemoryService shortTermMemoryService = mock(ShortTermMemoryService.class);
        when(shortTermMemoryService.loadForTurnWithMetadata(any(), anyString(), any()))
                .thenReturn(new ShortTermMemoryService.ShortTermMemoryLoadResult(
                        new ShortTermMemorySnapshot("", List.of()), false));

        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryAcquireSessionLock(any(), anyString())).thenReturn(true);

        RagRecall.Augmentation augmentation = mock(RagRecall.Augmentation.class);
        when(augmentation.buildAugmentation(anyString(), anyString(), any(), anyInt())).thenReturn(Optional.empty());

        ActiveChatModelContext activeChatModelContext = mock(ActiveChatModelContext.class);
        when(activeChatModelContext.activeModelName()).thenReturn("deepseek-v4-flash");
        when(activeChatModelContext.effectiveContextWindow()).thenReturn(32_768);

        ChatService service = new ChatService(
                chatAgent,
                mock(ChatMemoryStore.class),
                shortTermMemoryService,
                mock(MemoryCompressionService.class),
                mock(LongTermMemoryIngestionService.class),
                augmentation,
                new AgentProperties(),
                new MemoryProperties(),
                rateLimitService,
                mock(ChatMetadataService.class),
                mock(AgentStateStore.class),
                mock(AgentStateUpdater.class),
                new ChatMetrics(registry),
                new ObjectMapper(),
                new FishLlmProperties(),
                activeChatModelContext,
                new com.yuyu.fishagent.common.trace.TraceCollector(new com.yuyu.fishagent.common.trace.TraceProperties()),
                mock(com.yuyu.fishagent.common.trace.TraceEsWriter.class),
                mock(com.yuyu.fishagent.agent.tool.result.ToolResultGovernor.class));

        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(invocation -> {
            timeoutCallback.set(invocation.getArgument(0));
            return null;
        }).when(emitter).onTimeout(any(Runnable.class));

        service.streamChat("sid", "hello", emitter);
        timeoutCallback.get().run();

        assertThat(registry.find("fish.chat.turn.duration")
                .tag("outcome", "error")
                .timer()
                .count()).isEqualTo(1);
    }
}
