package com.yuyu.fishagent.auth.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.auth.context.UserContext;
import com.yuyu.fishagent.auth.config.AuthProperties;
import com.yuyu.fishagent.common.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 Redis 的会话存储：token → JSON(UserContext)，TTL 与 fish.auth.session-ttl-seconds 对齐。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSessionManager {

    private final StringRedisTemplate stringRedisTemplate;
    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    /**
     * 创建新会话并写入 Redis。
     *
     * @param ctx 用户信息（不含 token）
     * @return 随机生成的 token（UUID 无横线）
     */
    public String createSession(UserContext ctx) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = sessionKey(token);
        try {
            String json = objectMapper.writeValueAsString(ctx);
            stringRedisTemplate.opsForValue().set(key, json, ttl());
            return token;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("session serialize failed", e);
        }
    }

    /**
     * 根据 token 读取会话。
     *
     * @param token 前端传入的会话令牌
     * @return 用户上下文
     */
    public Optional<UserContext> getSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String raw = stringRedisTemplate.opsForValue().get(sessionKey(token));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, UserContext.class));
        } catch (JsonProcessingException e) {
            log.warn("[RedisSession] JSON 反序列化失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 刷新会话 TTL（活跃续期）。
     *
     * @param token 会话令牌
     */
    public void refreshTtl(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String key = sessionKey(token);
        Boolean expire = stringRedisTemplate.expire(key, ttl());
        if (Boolean.FALSE.equals(expire)) {
            log.debug("[RedisSession] 续期失败，key 可能已失效 {}", key);
        }
    }

    /**
     * 登出：删除 Redis 中的会话键。
     *
     * @param token 会话令牌
     */
    public void remove(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        stringRedisTemplate.delete(sessionKey(token));
    }

    private String sessionKey(String token) {
        if (RedisKeys.SESSION.equals(authProperties.getSessionKeyPrefix())) {
            return RedisKeys.session(token);
        }
        return authProperties.getSessionKeyPrefix() + ":" + token;
    }

    private Duration ttl() {
        long sec = Math.max(60L, authProperties.getSessionTtlSeconds());
        return Duration.ofSeconds(sec);
    }
}
