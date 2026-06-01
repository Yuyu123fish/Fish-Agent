package com.yuyu.fishagent.memory.shortterm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.rag.service.RustFsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 RustFS 的短期记忆 L2 快照存储；对象键 {@code {sessionId}.stm.json}，与对话正文 {@code {sessionId}.json} 同桶。
 * <p>sessionId 为全局唯一 UUID，故对象键不带 userId；归属校验在对话主链路已完成。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fish.rustfs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RustFsShortTermSnapshotStore implements ShortTermSnapshotStore {

    private final RustFsService rustFsService;
    /** 注入 Spring 管理的 ObjectMapper，避免 record / 模块注册与应用配置脱节。 */
    private final ObjectMapper objectMapper;

    @Override
    public ShortTermMemorySnapshot load(Long userId, String sessionId) {
        try {
            byte[] bytes = rustFsService.getChatJsonOrNull(objectKey(sessionId));
            if (bytes == null || bytes.length == 0) {
                return empty();
            }
            ShortTermMemorySnapshot snapshot = objectMapper.readValue(bytes, ShortTermMemorySnapshot.class);
            return snapshot == null ? empty() : snapshot;
        } catch (Exception e) {
            log.warn("[RustFsShortTermSnapshotStore] 读取快照失败 sid={}: {}", sessionId, e.getMessage());
            return empty();
        }
    }

    @Override
    public void save(Long userId, String sessionId, ShortTermMemorySnapshot snapshot) {
        try {
            ShortTermMemorySnapshot safeSnapshot = snapshot == null ? empty() : snapshot;
            byte[] data = objectMapper.writeValueAsBytes(safeSnapshot);
            rustFsService.putChatJson(objectKey(sessionId), data);
            log.debug("[RustFsShortTermSnapshotStore] 快照写入完成 sid={}, summaryLen={}, window={}",
                    sessionId,
                    safeSnapshot.summary() == null ? 0 : safeSnapshot.summary().length(),
                    safeSnapshot.recentMessages() == null ? 0 : safeSnapshot.recentMessages().size());
        } catch (Exception e) {
            log.warn("[RustFsShortTermSnapshotStore] 写入快照失败 sid={}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void clear(Long userId, String sessionId) {
        try {
            rustFsService.deleteChatJson(objectKey(sessionId));
        } catch (Exception e) {
            log.warn("[RustFsShortTermSnapshotStore] 删除快照失败 sid={}: {}", sessionId, e.getMessage());
        }
    }

    private static ShortTermMemorySnapshot empty() {
        return new ShortTermMemorySnapshot("", List.of());
    }

    private static String objectKey(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("invalid sessionId");
        }
        return sessionId + ".stm.json";
    }
}
