package com.yuyu.fishagent.common.trace;

/**
 * 链路追踪常量集中管理。
 * <p>业务代码只依赖这里的常量，后续若要接入网关 traceId 或 Micrometer Tracing，
 * 可以在入口层替换生成策略，而不用散改各个模块。</p>
 */
public final class TraceConstants {

    private TraceConstants() {
    }

    /** MDC 中保存请求追踪 ID 的 key。 */
    public static final String TRACE_ID = "traceId";

    /** 请求/响应头：支持前端、网关或调试工具传入链路 ID。 */
    public static final String HEADER_TRACE_ID = "X-Request-Id";

    /** SSE 首事件名：前端可展示或上报此 ID 辅助排障。 */
    public static final String SSE_EVENT_TRACE = "trace";
}
