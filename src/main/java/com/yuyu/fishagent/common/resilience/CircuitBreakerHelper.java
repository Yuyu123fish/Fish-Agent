package com.yuyu.fishagent.common.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 同步外部调用的熔断执行工具。
 * <p>RAG 文本召回、向量召回和 Reranker 都是同步阻塞调用，统一通过这里接入熔断器。
 * 熔断器打开时返回调用方传入的降级值；业务异常仍抛给调用方，由原有 catch/fallback 逻辑处理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerHelper {

    private final CircuitBreakerRegistry registry;

    /**
     * 在指定熔断器保护下执行同步调用。
     *
     * @param name     熔断器名称，见 {@link ResilienceConstants}
     * @param action   正常外部调用
     * @param fallback 熔断打开时直接返回的降级值
     * @param <T>      返回值类型
     * @return 正常调用结果或熔断降级值
     */
    public <T> T executeWithCircuitBreaker(String name, Supplier<T> action, T fallback) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker(name);
        try {
            return circuitBreaker.executeSupplier(action);
        } catch (CallNotPermittedException e) {
            log.warn("[CircuitBreaker] {} 已打开，使用降级值", name);
            return fallback;
        }
    }
}
