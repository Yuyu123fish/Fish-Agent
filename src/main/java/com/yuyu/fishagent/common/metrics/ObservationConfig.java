package com.yuyu.fishagent.common.metrics;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import com.yuyu.fishagent.common.trace.TraceObservationHandler;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Observation AOP 支持。
 * <p>Spring AI 的模型 Observation 由自动配置提供；该配置让项目内 {@code @Observed} 也能生效。</p>
 */
@Configuration
public class ObservationConfig {

    @Bean
    @Order
    ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }

    /**
     * 显式把 TurnTrace observation handler 接入 Micrometer registry。
     *
     * <p>{@code ObservationHandler} 作为普通 Spring bean 不会自动进入 registry；
     * 这里集中注册，保证 Spring AI 的 {@code gen_ai.*} observation 能被 trace 管线捕获。</p>
     */
    @Bean
    ObservationRegistryCustomizer<ObservationRegistry> traceObservationRegistryCustomizer(
            TraceObservationHandler handler) {
        return registry -> registry.observationConfig().observationHandler(handler);
    }
}
