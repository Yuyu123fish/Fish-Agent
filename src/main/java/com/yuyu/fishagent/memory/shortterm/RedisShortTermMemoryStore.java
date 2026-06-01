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
     * 将短期摘要和最近窗口写入 Redis，并统一设置 TTL。
     *
     * @param sessionId 会话 ID
     * @param summary 短期摘要，允许为空
     * @param recentMessages 最近消息窗口，按时间正序保存
     */
    @Override
    public void save(String sessionId, String summary, List<ChatMessageDTO> recentMessages) {
        // 优雅降级：Redis 不可用时静默跳过，不影响聊天流程
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.debug("[RedisShortTermMemoryStore] RedisTemplate 不可用，跳过短期记忆写入");
            return;
        }

        try {
            // 分别存储摘要和消息窗口，并设置统一的 TTL
            Duration ttl = Duration.ofDays(properties.getShortTermTtlDays());
            String summaryKey = summaryKey(sessionId);
            String messagesKey = messagesKey(sessionId);
            redisTemplate.opsForValue().set(summaryKey, summary == null ? "" : summary, ttl);
            redisTemplate.opsForValue().set(messagesKey, objectMapper.writeValueAsString(recentMessages), ttl);
            log.debug("[RedisShortTermMemoryStore] 短期记忆写入完成 sid={}, summaryKey={}, messagesKey={}, summaryLen={}, recentMessages={}, ttlDays={}",
                    sessionId, summaryKey, messagesKey, summary == null ? 0 : summary.length(),
                    recentMessages == null ? 0 : recentMessages.size(), properties.getShortTermTtlDays());
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
            // 从 Redis 读取摘要和最近消息窗口
            String summaryKey = summaryKey(sessionId);
            String messagesKey = messagesKey(sessionId);
            String summary = redisTemplate.opsForValue().get(summaryKey);
            String messagesJson = redisTemplate.opsForValue().get(messagesKey);
            // 反序列化消息列表
            List<ChatMessageDTO> messages = messagesJson == null || messagesJson.isBlank()
                    ? List.of()
                    : objectMapper.readValue(messagesJson, MESSAGE_LIST_TYPE);
            log.debug("[RedisShortTermMemoryStore] 短期记忆读取完成 sid={}, summaryHit={}, messagesCount={}",
                    sessionId, summary != null && !summary.isBlank(), messages.size());
            return new ShortTermMemorySnapshot(summary == null ? "" : summary, messages);
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

    /**
     * 生成最近消息窗口 key，和摘要分开存储便于后续独立调整格式。
     */
    private String messagesKey(String sessionId) {
        return properties.getRedisKeyPrefix() + ":short:" + sessionId + ":messages";
    }
}
