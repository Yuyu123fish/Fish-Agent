package com.yuyu.fishagent.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级 traceId 注入过滤器。
 * <p>放在 Filter 链最前面，保证鉴权、限流、Controller 和 Service 的同步日志都能自动带上同一个 traceId。</p>
 * <p>SSE 返回后 Servlet 线程会结束，因此长生命周期异步逻辑需要在提交任务前通过 {@link MdcAsync} 捕获 MDC 快照。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(TraceConstants.TRACE_ID, traceId);
        response.setHeader(TraceConstants.HEADER_TRACE_ID, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 仅移除本过滤器写入的 key，避免误清理同线程上游组件放入的其他 MDC 字段。
            MDC.remove(TraceConstants.TRACE_ID);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String requestTraceId = request.getHeader(TraceConstants.HEADER_TRACE_ID);
        if (requestTraceId != null && !requestTraceId.isBlank()) {
            return requestTraceId.trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
