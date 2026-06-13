package com.yuyu.fishagent.common.metrics;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
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
}
