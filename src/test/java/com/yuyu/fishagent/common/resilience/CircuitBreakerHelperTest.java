package com.yuyu.fishagent.common.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerHelperTest {

    @Test
    void shouldReturnFallbackWhenCircuitBreakerIsOpen() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreakerHelper helper = new CircuitBreakerHelper(registry);
        registry.circuitBreaker(ResilienceConstants.CB_ES_TEXT)
                .transitionToOpenState();

        String result = helper.executeWithCircuitBreaker(
                ResilienceConstants.CB_ES_TEXT,
                () -> "real",
                "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void shouldPropagateBusinessExceptionWhenCircuitBreakerAllowsCall() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreakerHelper helper = new CircuitBreakerHelper(registry);

        assertThatThrownBy(() -> helper.executeWithCircuitBreaker(
                ResilienceConstants.CB_ES_VECTOR,
                () -> {
                    throw new IllegalStateException("upstream failed");
                },
                "fallback"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("upstream failed");
    }
}
