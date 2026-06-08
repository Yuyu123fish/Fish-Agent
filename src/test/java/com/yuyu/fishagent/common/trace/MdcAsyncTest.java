package com.yuyu.fishagent.common.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MdcAsyncTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldPropagateMdcToSupplyAsyncAndClearWorkerThreadAfterwards() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            MDC.put(TraceConstants.TRACE_ID, "trace-async");

            String traceId = MdcAsync.mdcSupplyAsync(() -> MDC.get(TraceConstants.TRACE_ID), executor).join();
            Map<String, String> workerMdcAfterTask = CompletableFuture.supplyAsync(MDC::getCopyOfContextMap, executor).join();

            assertThat(traceId).isEqualTo("trace-async");
            assertThat(workerMdcAfterTask).isNull();
            assertThat(MDC.get(TraceConstants.TRACE_ID)).isEqualTo("trace-async");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldPropagateMdcToRunAsync() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            MDC.put(TraceConstants.TRACE_ID, "trace-run");
            AtomicReference<String> traceId = new AtomicReference<>();

            MdcAsync.mdcRunAsync(() -> traceId.set(MDC.get(TraceConstants.TRACE_ID)), executor).join();

            assertThat(traceId).hasValue("trace-run");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldPropagateExplicitMdcSnapshotWhenCallerThreadHasNoMdc() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Map<String, String> streamSnapshot = Map.of(TraceConstants.TRACE_ID, "trace-sse");
            AtomicReference<String> traceId = new AtomicReference<>();

            MdcAsync.mdcRunAsync(() -> traceId.set(MDC.get(TraceConstants.TRACE_ID)), executor, streamSnapshot).join();

            assertThat(traceId).hasValue("trace-sse");
            assertThat(MDC.get(TraceConstants.TRACE_ID)).isNull();
        } finally {
            executor.shutdownNow();
        }
    }
}
