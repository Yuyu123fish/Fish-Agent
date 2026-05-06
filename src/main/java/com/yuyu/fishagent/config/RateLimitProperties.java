package com.yuyu.fishagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话限流配置：{@code fish.rate-limit.*}。
 * <p>嵌套 record 无无参构造器，字段默认值使用显式构造参数；YAML 存在时由 Spring Boot 绑定覆盖。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    /** 令牌桶：容量、每秒补充速率、Redis key TTL（秒）。 */
    private TokenBucket tokenBucket = new TokenBucket(60, 1.0, 120);

    /** SSE 并发：最大连接数、计数 key TTL（秒，兜底防泄漏）。 */
    private SseConcurrent sseConcurrent = new SseConcurrent(2, 300);

    public record TokenBucket(int capacity, double refillRate, int keyTtl) {}

    public record SseConcurrent(int maxConnections, int keyTtl) {}
}
