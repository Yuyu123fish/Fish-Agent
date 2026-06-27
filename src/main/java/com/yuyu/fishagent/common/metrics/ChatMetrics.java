package com.yuyu.fishagent.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 对话业务指标注册入口。
 * <p>JVM、HTTP、缓存、熔断、连接池等通用指标由 Actuator/Micrometer 自动绑定；这里仅维护
 * Fish 自有的对话端到端耗时与 RAG 拓扑聚合耗时，避免指标名散落在业务代码中。</p>
 */
@Component
public class ChatMetrics {

    public enum Outcome {
        SUCCESS("success"),
        ERROR("error");

        private final String tagValue;

        Outcome(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    public enum RagLeg {
        TEXT("text"),
        VECTOR("vector"),
        FUSION("fusion"),
        RERANK("rerank"),
        PROVENANCE("provenance"),
        EXPAND("expand");

        private final String tagValue;

        RagLeg(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    private final MeterRegistry registry;

    public ChatMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 开始一次可跨线程停止的采样，适配 SseEmitter + Reactor subscribe 回调模型。 */
    public Timer.Sample startSample() {
        return Timer.start(registry);
    }

    /** 单轮对话端到端耗时：含上下文组装、RAG、LLM、工具、持久化和异步收尾前的主链路。 */
    public Timer chatTurnTimer(Outcome outcome) {
        return Timer.builder("fish.chat.turn.duration")
                .description("End-to-end duration of a streamed chat turn")
                .tag("outcome", outcome.tagValue)
                .register(registry);
    }

    /** RAG 各腿耗时聚合：逐请求质量明细仍由 RagQualityLogger 写入 ES。 */
    public Timer ragLegTimer(RagLeg leg) {
        return Timer.builder("fish.rag.recall.duration")
                .description("Duration of RAG recall topology legs")
                .tag("leg", leg.tagValue)
                .register(registry);
    }
}
