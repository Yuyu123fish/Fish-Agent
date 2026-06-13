package com.yuyu.fishagent.memory.agentstate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.common.redis.RedisKeys;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 基于 Redis 的 Agent 状态存储。
 * Key: fish:memory:agent-state:{sessionId}
 * TTL: 与短期记忆一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisAgentStateStore implements AgentStateStore {

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;
    private final MemoryProperties properties;

    @Override
    public SessionAgentState load(Long userId, String sessionId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return emptyState();
        }
        try {
            String json = redis.opsForValue().get(key(sessionId));
            if (json == null || json.isBlank()) {
                return emptyState();
            }
            SessionAgentState state = objectMapper.readValue(json, SessionAgentState.class);
            return state == null ? emptyState() : state;
        } catch (Exception e) {
            log.warn("[RedisAgentStateStore] 读取失败 sid={}: {}", sessionId, e.getMessage());
            return emptyState();
        }
    }

    @Override
    public void save(Long userId, String sessionId, SessionAgentState state) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null || state == null) {
            return;
        }
        try {
            redis.opsForValue().set(
                    key(sessionId),
                    objectMapper.writeValueAsString(state),
                    Duration.ofDays(properties.getShortTermTtlDays())
            );
            log.debug("[RedisAgentStateStore] 写入完成 sid={}, phase={}, tasks={}",
                    sessionId, state.phase(), state.activeTasks().size());
        } catch (Exception e) {
            log.warn("[RedisAgentStateStore] 写入失败 sid={}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void clear(Long userId, String sessionId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.delete(key(sessionId));
        } catch (Exception e) {
            log.warn("[RedisAgentStateStore] 删除失败 sid={}: {}", sessionId, e.getMessage());
        }
    }

    private String key(String sessionId) {
        if (RedisKeys.MEMORY.equals(properties.getRedisKeyPrefix())) {
            return RedisKeys.memoryAgentState(sessionId);
        }
        return properties.getRedisKeyPrefix() + ":agent-state:" + sessionId;
    }

    private static SessionAgentState emptyState() {
        return new SessionAgentState("IDLE", List.of(), List.of(), null, Instant.now());
    }
}
