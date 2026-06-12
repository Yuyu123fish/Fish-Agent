package com.yuyu.fishagent.memory.shortterm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Redis 的短期记忆存储。
 * <p>Redis 不可用时直接跳过读写，让记忆压缩接口仍能返回模型结果。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisShortTermMemoryStore implements ShortTermMemoryStore {

    private static final TypeReference<List<ChatMessageDTO>> MESSAGE_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;
    private final MemoryProperties properties;

    /**
     * 将完整短期记忆快照写入 Redis，并统一设置 TTL。
     *
     * @param sessionId 会话 ID
     * @param snapshot 短期记忆快照
     */
    @Override
    public void save(String sessionId, ShortTermMemorySnapshot snapshot) {
        // 优雅降级：Redis 不可用时静默跳过，不影响聊天流程
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.debug("[RedisShortTermMemoryStore] RedisTemplate 不可用，跳过短期记忆写入");
            return;
        }

        try {
            Duration ttl = Duration.ofDays(properties.getShortTermTtlDays());
            ShortTermMemorySnapshot safeSnapshot = snapshot == null ? empty() : snapshot;
            redisTemplate.opsForValue().set(snapshotKey(sessionId),
                    objectMapper.writeValueAsString(safeSnapshot), ttl);
            log.debug("[RedisShortTermMemoryStore] 短期记忆写入完成 sid={}, hasSummary={}, excerpts={}, recentMessages={}, incCount={}",
                    sessionId,
                    safeSnapshot.structuredSummary() != null,
                    safeSnapshot.keyExcerpts().size(),
                    safeSnapshot.recentMessages().size(),
                    safeSnapshot.incrementalCount());
        } catch (Exception e) {
            log.warn("[RedisShortTermMemoryStore] 写入短期记忆失败 sid={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 从 Redis 读取短期记忆。读取失败时返回空快照，让聊天流程回退到文件历史窗口。
     *
     * @param sessionId 会话 ID
     * @return Redis 中的短期摘要和最近消息窗口
     */
    @Override
    public ShortTermMemorySnapshot load(String sessionId) {
        // 优雅降级：Redis 不可用时返回空快照，让上层回退到文件历史
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.debug("[RedisShortTermMemoryStore] RedisTemplate 不可用，返回空短期记忆 sid={}", sessionId);
            return new ShortTermMemorySnapshot("", List.of());
        }

        try {
            String json = redisTemplate.opsForValue().get(snapshotKey(sessionId));
            if (json != null && !json.isBlank()) {
                try {
                    ShortTermMemorySnapshot snapshot = objectMapper.readValue(json, ShortTermMemorySnapshot.class);
                    log.debug("[RedisShortTermMemoryStore] 新格式短期记忆读取完成 sid={}, hasSummary={}, messagesCount={}",
                            sessionId, snapshot.structuredSummary() != null, snapshot.recentMessages().size());
                    return snapshot;
                } catch (Exception parseException) {
                    log.warn("[RedisShortTermMemoryStore] 新格式短期记忆解析失败，尝试旧格式 sid={}: {}",
                            sessionId, parseException.getMessage());
                }
            }
            return loadLegacyFormat(redisTemplate, sessionId);
        } catch (Exception e) {
            log.warn("[RedisShortTermMemoryStore] 读取短期记忆失败 sid={}: {}", sessionId, e.getMessage());
            return new ShortTermMemorySnapshot("", List.of());
        }
    }

    /**
     * 删除该会话的摘要与窗口两个 key。Redis 不可用时静默跳过。
     */
    @Override
    public void clear(String sessionId) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.debug("[RedisShortTermMemoryStore] RedisTemplate 不可用，跳过短期记忆删除 sid={}", sessionId);
            return;
        }
        try {
            redisTemplate.delete(snapshotKey(sessionId));
            redisTemplate.delete(summaryKey(sessionId));
            redisTemplate.delete(messagesKey(sessionId));
            log.debug("[RedisShortTermMemoryStore] 短期记忆已删除 sid={}", sessionId);
        } catch (Exception e) {
            log.warn("[RedisShortTermMemoryStore] 删除短期记忆失败 sid={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 生成短期摘要 key，按 session 维度隔离。
     */
    private String summaryKey(String sessionId) {
        return properties.getRedisKeyPrefix() + ":short:" + sessionId + ":summary";
    }

    private String snapshotKey(String sessionId) {
        return properties.getRedisKeyPrefix() + ":short:" + sessionId + ":snapshot";
    }

    /**
     * 生成最近消息窗口 key，和摘要分开存储便于后续独立调整格式。
     */
    private String messagesKey(String sessionId) {
        return properties.getRedisKeyPrefix() + ":short:" + sessionId + ":messages";
    }

    /**
     * 兼容旧 Redis 格式：summary 和 messages 分别存储。
     */
    private ShortTermMemorySnapshot loadLegacyFormat(StringRedisTemplate redisTemplate, String sessionId) throws Exception {
        String summary = redisTemplate.opsForValue().get(summaryKey(sessionId));
        String messagesJson = redisTemplate.opsForValue().get(messagesKey(sessionId));
        List<ChatMessageDTO> messages = messagesJson == null || messagesJson.isBlank()
                ? List.of()
                : objectMapper.readValue(messagesJson, MESSAGE_LIST_TYPE);
        log.debug("[RedisShortTermMemoryStore] 旧格式短期记忆读取完成 sid={}, summaryHit={}, messagesCount={}",
                sessionId, summary != null && !summary.isBlank(), messages.size());
        return new ShortTermMemorySnapshot(summary == null ? "" : summary, messages);
    }

    private static ShortTermMemorySnapshot empty() {
        return new ShortTermMemorySnapshot(null, List.of(), List.of(), 0);
    }
}
