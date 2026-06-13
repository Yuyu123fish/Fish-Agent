package com.yuyu.fishagent.common.trace;

import com.yuyu.fishagent.common.metrics.ChatMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内 turn trace 收集器。
 *
 * <p>它只保存正在进行中的 turn，结束后由 writer 异步落 ES 并移除，避免长期 LRU/缓存状态。</p>
 */
@Component
@RequiredArgsConstructor
public class TraceCollector {

    private final TraceProperties properties;
    private final Map<String, TurnTrace> active = new ConcurrentHashMap<>();

    public TurnTrace startTurn(String turnId, String sessionId, String traceId) {
        TurnTrace trace = new TurnTrace();
        trace.setTurnId(turnId);
        trace.setSessionId(sessionId);
        trace.setTraceId(traceId);
        trace.setStartTimeMs(System.currentTimeMillis());
        active.put(turnId, trace);
        return trace;
    }

    public TurnTrace current(String turnId) {
        return active.get(turnId);
    }

    public TurnTrace remove(String turnId) {
        return active.remove(turnId);
    }

    public void recordRagInjected(String turnId, String text) {
        TurnTrace trace = active.get(turnId);
        if (trace != null) {
            trace.setRagInjected(snippet(text));
        }
    }

    public void recordMemoryInjected(String turnId, String text) {
        TurnTrace trace = active.get(turnId);
        if (trace != null) {
            trace.setMemoryInjected(snippet(text));
        }
    }

    public void recordNode(String turnId, String nodeName, String type, String content, long latencyMs, String status) {
        recordNode(turnId, nodeName, type, content, latencyMs, status, null);
    }

    public void recordNode(String turnId, String nodeName, String type, String content,
                           long latencyMs, String status, String disposition) {
        TurnTrace trace = active.get(turnId);
        if (trace == null) {
            return;
        }
        TurnTrace.Node node = new TurnTrace.Node();
        node.setOrder(trace.getNextNodeOrder().getAndIncrement());
        node.setNodeName(nodeName);
        node.setType(type);
        node.setContentSnippet(snippet(content));
        node.setLatencyMs(Math.max(0, latencyMs));
        node.setStatus(status == null ? "SUCCESS" : status);
        node.setDisposition(disposition);
        trace.getNodes().add(node);
    }

    public TurnTrace finishTurn(String turnId, ChatMetrics.Outcome outcome) {
        TurnTrace trace = active.get(turnId);
        if (trace == null || !trace.getCompleted().compareAndSet(false, true)) {
            return trace;
        }
        trace.setStatus(outcome == ChatMetrics.Outcome.SUCCESS ? "SUCCESS" : "ERROR");
        trace.setTotalLatencyMs(Math.max(0, System.currentTimeMillis() - trace.getStartTimeMs()));
        return trace;
    }

    private String snippet(String text) {
        if (text == null) {
            return null;
        }
        int max = Math.max(20, properties.getSnippetMaxChars());
        return text.length() <= max ? text : text.substring(0, max);
    }
}
