package com.yuyu.fishagent.common.trace;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * CompletableFuture 的 MDC 传播工具。
 * <p>MDC 基于 ThreadLocal，任务切到虚拟线程或公共线程池后不会自动继承当前请求的 traceId。
 * 本工具在提交异步任务时捕获调用线程 MDC 快照，并在任务线程中临时恢复，任务结束后再清理。</p>
 */
public final class MdcAsync {

    private MdcAsync() {
    }

    /**
     * 使用指定执行器提交带 MDC 传播的异步 Supplier。
     */
    public static <T> CompletableFuture<T> mdcSupplyAsync(Supplier<T> supplier, Executor executor) {
        return CompletableFuture.supplyAsync(wrapSupplier(supplier), executor);
    }

    /**
     * 使用指定 MDC 快照和执行器提交异步 Supplier。
     * <p>适用于 SSE / Reactor 回调这类“当前线程 MDC 已清理，但入口处已保存快照”的场景。</p>
     */
    public static <T> CompletableFuture<T> mdcSupplyAsync(Supplier<T> supplier,
                                                          Executor executor,
                                                          Map<String, String> contextMap) {
        return CompletableFuture.supplyAsync(wrapSupplier(supplier, contextMap), executor);
    }

    /**
     * 使用公共线程池提交带 MDC 传播的异步 Supplier。
     */
    public static <T> CompletableFuture<T> mdcSupplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(wrapSupplier(supplier));
    }

    /**
     * 使用指定执行器提交带 MDC 传播的异步 Runnable。
     */
    public static CompletableFuture<Void> mdcRunAsync(Runnable runnable, Executor executor) {
        return CompletableFuture.runAsync(wrapRunnable(runnable), executor);
    }

    /**
     * 使用指定 MDC 快照和执行器提交异步 Runnable。
     */
    public static CompletableFuture<Void> mdcRunAsync(Runnable runnable,
                                                      Executor executor,
                                                      Map<String, String> contextMap) {
        return CompletableFuture.runAsync(wrapRunnable(runnable, contextMap), executor);
    }

    /**
     * 使用指定 MDC 快照提交异步 Runnable。
     */
    public static CompletableFuture<Void> mdcRunAsync(Runnable runnable, Map<String, String> contextMap) {
        return CompletableFuture.runAsync(wrapRunnable(runnable, contextMap));
    }

    /**
     * 使用公共线程池提交带 MDC 传播的异步 Runnable。
     */
    public static CompletableFuture<Void> mdcRunAsync(Runnable runnable) {
        return CompletableFuture.runAsync(wrapRunnable(runnable));
    }

    /**
     * 包装 Supplier：提交点捕获 MDC，执行点恢复 MDC。
     * <p>执行完成后清理线程 MDC，避免线程复用时 traceId 泄漏到下一次任务。</p>
     */
    public static <T> Supplier<T> wrapSupplier(Supplier<T> supplier) {
        return wrapSupplier(supplier, MDC.getCopyOfContextMap());
    }

    /**
     * 使用外部传入的 MDC 快照包装 Supplier。
     */
    public static <T> Supplier<T> wrapSupplier(Supplier<T> supplier, Map<String, String> contextMap) {
        Map<String, String> capturedContext = copyContext(contextMap);
        return () -> {
            restoreCapturedContext(capturedContext);
            try {
                return supplier.get();
            } finally {
                MDC.clear();
            }
        };
    }

    /**
     * 包装 Runnable，便于未来接入 Executor 装饰器或定时任务时复用同一套 MDC 传播逻辑。
     */
    public static Runnable wrapRunnable(Runnable runnable) {
        return wrapRunnable(runnable, MDC.getCopyOfContextMap());
    }

    /**
     * 使用外部传入的 MDC 快照包装 Runnable。
     */
    public static Runnable wrapRunnable(Runnable runnable, Map<String, String> contextMap) {
        Map<String, String> capturedContext = copyContext(contextMap);
        return () -> {
            restoreCapturedContext(capturedContext);
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }

    private static void restoreCapturedContext(Map<String, String> capturedContext) {
        if (capturedContext == null || capturedContext.isEmpty()) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(capturedContext);
    }

    private static Map<String, String> copyContext(Map<String, String> contextMap) {
        return contextMap == null ? null : Map.copyOf(contextMap);
    }
}
