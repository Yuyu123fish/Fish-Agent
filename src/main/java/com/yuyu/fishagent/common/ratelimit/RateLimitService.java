package com.yuyu.fishagent.common.ratelimit;

import com.yuyu.fishagent.common.config.RateLimitProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 基于 Redis Lua 的对话限流：令牌桶（每分钟等价速率）与 SSE 并发计数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final String TOKEN_KEY_PREFIX = "fish:ratelimit:token:";
    private static final String SSE_KEY_PREFIX = "fish:ratelimit:sse:";

    /** 会话级互斥：与 ChatService 流式对话一一对应，防止同一 sessionId 并发跑两路主循环。 */
    private static final String SESSION_MUTEX_KEY_PREFIX = "fish:mutex:session:";

    /** 会话锁兜底 TTL（秒）：正常路径在 SSE 结束时主动删除，避免进程崩溃导致永久死锁。 */
    private static final int SESSION_MUTEX_TTL_SECONDS = 120;

    /**
     * 令牌桶：原子 refill + consume；返回 1=放行，0=拒绝。
     */
    private static final String LUA_TOKEN_BUCKET = """
            local key    = KEYS[1]
            local cap    = tonumber(ARGV[1])
            local rate   = tonumber(ARGV[2])
            local now    = tonumber(ARGV[3])
            local need   = tonumber(ARGV[4])
            local ttl    = tonumber(ARGV[5])

            local data       = redis.call('HMGET', key, 'tokens', 'lastRefillTime')
            local tokens     = tonumber(data[1]) or cap
            local lastRefill = tonumber(data[2]) or now

            local elapsed = math.max(0, now - lastRefill)
            tokens = math.min(cap, tokens + elapsed * rate / 1000)

            local allowed = 0
            if tokens >= need then
                tokens  = tokens - need
                allowed = 1
            end

            redis.call('HSET', key, 'tokens', tokens, 'lastRefillTime', now)
            redis.call('EXPIRE', key, ttl)
            return allowed
            """;

    /**
     * SSE 并发：INCR 后若超过上限则 DECR 回滚并返回 -1。
     */
    private static final String LUA_SSE_TRY_INCREMENT = """
            local key = KEYS[1]
            local max = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[2])

            local cur = redis.call('INCR', key)
            redis.call('EXPIRE', key, ttl)
            if cur > max then
                redis.call('DECR', key)
                return -1
            end
            return cur
            """;

    /** SSE 结束：计数安全递减（不小于 0）。 */
    private static final String LUA_SSE_DECREMENT = """
            local key = KEYS[1]
            local v = tonumber(redis.call('GET', key))
            if v == nil or v <= 0 then
                return 0
            end
            return redis.call('DECR', key)
            """;

    private final RateLimitProperties properties;
    private final StringRedisTemplate stringRedisTemplate;

    private final DefaultRedisScript<Long> tokenBucketScript = script(LUA_TOKEN_BUCKET);
    private final DefaultRedisScript<Long> sseTryIncrScript = script(LUA_SSE_TRY_INCREMENT);
    private final DefaultRedisScript<Long> sseDecrScript = script(LUA_SSE_DECREMENT);

    /**
     * 按顺序执行：令牌桶 → （可选）占用 SSE 槽位。
     *
     * @param userId        当前用户 ID
     * @param acquireSseSlot 是否为 {@code POST /api/chat/stream}，需要占用 SSE 并发计数
     */
    public RateLimitResult evaluate(long userId, boolean acquireSseSlot) {
        if (!properties.isEnabled()) {
            return new RateLimitResult.Allowed();
        }
        RateLimitProperties.TokenBucket tb = properties.getTokenBucket();
        RateLimitProperties.SseConcurrent sc = properties.getSseConcurrent();
        try {
            List<String> tokenKey = List.of(TOKEN_KEY_PREFIX + userId);
            long now = System.currentTimeMillis();
            Long allowed = stringRedisTemplate.execute(
                    tokenBucketScript,
                    tokenKey,
                    String.valueOf(tb.capacity()),
                    String.valueOf(tb.refillRate()),
                    String.valueOf(now),
                    "1",
                    String.valueOf(tb.keyTtl()));

            if (allowed == null || allowed == 0L) {
                return new RateLimitResult.TokenBucketDenied(estimateRetryAfterSeconds(tb.refillRate()));
            }

            if (acquireSseSlot) {
                List<String> sseKey = List.of(SSE_KEY_PREFIX + userId);
                Long sseSlot = stringRedisTemplate.execute(
                        sseTryIncrScript,
                        sseKey,
                        String.valueOf(sc.maxConnections()),
                        String.valueOf(sc.keyTtl()));
                if (sseSlot == null || sseSlot < 0L) {
                    return new RateLimitResult.ConcurrentDenied();
                }
            }

            return new RateLimitResult.Allowed();
        } catch (Exception e) {
            log.warn("[RateLimitService] Redis 限流执行失败 userId={}, sseSlot={}, err={}",
                    userId, acquireSseSlot, e.getMessage());
            return new RateLimitResult.Allowed();
        }
    }

    /**
     * 释放 SSE 并发计数（连接结束或中途异常退出时调用）；幂等安全。
     */
    public void decrementSseConcurrent(long userId) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            stringRedisTemplate.execute(
                    sseDecrScript,
                    Collections.singletonList(SSE_KEY_PREFIX + userId));
        } catch (Exception e) {
            log.warn("[RateLimitService] SSE 计数递减失败 userId={}, err={}", userId, e.getMessage());
        }
    }

    /**
     * 尝试占用会话级分布式锁（SET NX EX）。
     * <p>Redis 异常时 fail-open 返回 true，避免限流基础设施故障阻断全部对话。</p>
     *
     * @param userId    当前用户（仅用于日志；锁 key 仅按 sessionId）
     * @param sessionId 会话 ID
     * @return true 表示获得锁；false 表示该会话已有进行中的流
     */
    public boolean tryAcquireSessionLock(Long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return true;
        }
        try {
            Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(
                    SESSION_MUTEX_KEY_PREFIX + sessionId,
                    "1",
                    Duration.ofSeconds(SESSION_MUTEX_TTL_SECONDS));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("[RateLimitService] 会话锁获取异常 userId={} sid={}, fail-open: {}",
                    userId, sessionId, e.getMessage());
            return true;
        }
    }

    /**
     * 释放会话锁（DEL）。幂等：key 不存在时不报错。
     *
     * @param userId    当前用户（日志用）
     * @param sessionId 会话 ID
     */
    public void releaseSessionLock(Long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            stringRedisTemplate.delete(SESSION_MUTEX_KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("[RateLimitService] 会话锁释放异常 userId={} sid={}: {}", userId, sessionId, e.getMessage());
        }
    }

    private static int estimateRetryAfterSeconds(double refillRatePerSecond) {
        if (refillRatePerSecond <= 0) {
            return 60;
        }
        return Math.max(1, (int) Math.ceil(1.0 / refillRatePerSecond));
    }

    private static DefaultRedisScript<Long> script(String lua) {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptText(lua);
        s.setResultType(Long.class);
        return s;
    }
}
