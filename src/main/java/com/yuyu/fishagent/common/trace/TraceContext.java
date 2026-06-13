package com.yuyu.fishagent.common.trace;

/**
 * 当前 turnId 的轻量 ThreadLocal 桥。
 *
 * <p>ChatAgent 节点 tap 显式传 turnId；ObservationHandler 只能从当前线程读取，
 * 因此这里作为 MVP 桥接点。后续可替换为 Reactor Context / Micrometer ContextPropagation。</p>
 */
public final class TraceContext {

    private static final ThreadLocal<String> TURN_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    public static void setTurnId(String turnId) {
        TURN_ID.set(turnId);
    }

    public static String currentTurnId() {
        return TURN_ID.get();
    }

    public static void clear() {
        TURN_ID.remove();
    }
}
