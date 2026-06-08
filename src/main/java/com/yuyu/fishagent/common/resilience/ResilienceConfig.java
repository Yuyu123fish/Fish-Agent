package com.yuyu.fishagent.common.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j 事件日志配置。
 * <p>{@link CircuitBreakerRegistry} 由 resilience4j-spring-boot3 根据 application.yml 自动创建，
 * 这里不手动 new registry，避免覆盖 YAML 中的阈值、窗口和半开配置。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ResilienceConfig {

    private final CircuitBreakerRegistry registry;

    @PostConstruct
    public void registerEventLogging() {
        registry.getAllCircuitBreakers().forEach(this::attachEventLogging);
        registry.getEventPublisher().onEntryAdded(event -> attachEventLogging(event.getAddedEntry()));
    }

    private void attachEventLogging(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("[CircuitBreaker] {} 状态变更: {} -> {}",
                        event.getCircuitBreakerName(),
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()))
                .onError(event -> log.warn("[CircuitBreaker] {} 调用失败 durationMs={}, error={}",
                        event.getCircuitBreakerName(),
                        event.getElapsedDuration().toMillis(),
                        event.getThrowable() == null ? "unknown" : event.getThrowable().getMessage()));
    }
}
