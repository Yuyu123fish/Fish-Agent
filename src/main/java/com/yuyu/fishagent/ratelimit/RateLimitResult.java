package com.yuyu.fishagent.ratelimit;

/**
 * 限流判定结果：放行、令牌桶拒绝或 SSE 并发拒绝。
 */
public sealed interface RateLimitResult permits RateLimitResult.Allowed,
        RateLimitResult.TokenBucketDenied, RateLimitResult.ConcurrentDenied {

    record Allowed() implements RateLimitResult {}

    /** 频率限制；{@code retryAfterSeconds} 供前端提示稍后重试。 */
    record TokenBucketDenied(int retryAfterSeconds) implements RateLimitResult {}

    /** 并发 SSE 连接数超限。 */
    record ConcurrentDenied() implements RateLimitResult {}
}
