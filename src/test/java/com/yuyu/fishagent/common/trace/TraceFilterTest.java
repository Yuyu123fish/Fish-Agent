package com.yuyu.fishagent.common.trace;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldUseRequestHeaderAsTraceIdAndReturnItInResponse() throws ServletException, IOException {
        TraceFilter filter = new TraceFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/card/stats");
        request.addHeader(TraceConstants.HEADER_TRACE_ID, "trace-from-client");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                traceIdInsideChain.set(MDC.get(TraceConstants.TRACE_ID)));

        assertThat(traceIdInsideChain).hasValue("trace-from-client");
        assertThat(response.getHeader(TraceConstants.HEADER_TRACE_ID)).isEqualTo("trace-from-client");
        assertThat(MDC.get(TraceConstants.TRACE_ID)).isNull();
    }

    @Test
    void shouldGenerateTraceIdWhenRequestHeaderIsBlank() throws ServletException, IOException {
        TraceFilter filter = new TraceFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/card/stats");
        request.addHeader(TraceConstants.HEADER_TRACE_ID, " ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                traceIdInsideChain.set(MDC.get(TraceConstants.TRACE_ID)));

        assertThat(traceIdInsideChain.get()).matches("[0-9a-f]{32}");
        assertThat(response.getHeader(TraceConstants.HEADER_TRACE_ID)).isEqualTo(traceIdInsideChain.get());
        assertThat(MDC.get(TraceConstants.TRACE_ID)).isNull();
    }
}
