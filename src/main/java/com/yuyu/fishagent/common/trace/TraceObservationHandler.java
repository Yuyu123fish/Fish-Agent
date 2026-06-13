package com.yuyu.fishagent.common.trace;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Spring AI Observation 到 TurnTrace 的轻量桥接。
 *
 * <p>MVP 先记录 gen_ai observation 结束事件；token 字段名在不同 Spring AI 版本可能变化，
 * 因此 token 解析保守处理，避免因为 key 不稳定影响主链路。</p>
 */
@Component
@RequiredArgsConstructor
public class TraceObservationHandler implements ObservationHandler<Observation.Context> {

    private final TraceCollector collector;

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context != null && context.getName() != null && context.getName().startsWith("gen_ai");
    }

    @Override
    public void onStop(Observation.Context context) {
        String turnId = TraceContext.currentTurnId();
        if (turnId == null) {
            return;
        }
        collector.recordNode(turnId, context.getName(), "llm", "Spring AI observation stopped", 0, "SUCCESS");
    }
}
