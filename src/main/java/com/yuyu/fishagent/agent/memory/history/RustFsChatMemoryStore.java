package com.yuyu.fishagent.agent.memory.history;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.dto.ChatMessageDTO;
import com.yuyu.fishagent.dto.SessionInfo;
import com.yuyu.fishagent.service.ChatMetadataService;
import com.yuyu.fishagent.service.storage.RustFsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 RustFS 的会话 JSON 存储；对象键 {@code {sessionId}.json}，所有权由 {@link ChatMetadataService} 约束。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fish.rustfs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RustFsChatMemoryStore implements ChatMemoryStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ChatMessageDTO>> LIST_TYPE = new TypeReference<>() {};

    private final RustFsService rustFsService;
    private final ChatMetadataService chatMetadataService;

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ChatMessageDTO> load(String sessionId) {
        chatMetadataService.assertReadableSessionOrNew(sessionId);
        ReentrantLock lock = lockOf(sessionId);
        lock.lock();
        try {
            return loadUnlocked(sessionId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void append(String sessionId, ChatMessageDTO message) {
        appendAll(sessionId, List.of(message));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void appendAll(String sessionId, List<ChatMessageDTO> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String preview = firstUserPreview(messages);
        chatMetadataService.ensureSessionForCurrentUser(sessionId, preview);

        ReentrantLock lock = lockOf(sessionId);
        lock.lock();
        try {
            List<ChatMessageDTO> all = loadUnlocked(sessionId);
            all.addAll(messages);
            byte[] data = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(all);
            rustFsService.putChatJson(objectKey(sessionId), data);
            chatMetadataService.touchUpdatedAt(sessionId);
        } catch (Exception e) {
            log.error("[ChatMemory.RustFs] 写入失败 sid={}: {}", sessionId, e.getMessage(), e);
            throw new IllegalStateException("rustfs write failed", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear(String sessionId) {
        chatMetadataService.assertOwnedByCurrentUser(sessionId);
        chatMetadataService.deleteMetadataForCurrentUser(sessionId);
        ReentrantLock lock = lockOf(sessionId);
        lock.lock();
        try {
            rustFsService.deleteChatJson(objectKey(sessionId));
        } catch (Exception e) {
            log.warn("[ChatMemory.RustFs] 删除对象失败 sid={}: {}", sessionId, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<SessionInfo> listSessions() {
        return chatMetadataService.listSessionsForCurrentUser();
    }

    private List<ChatMessageDTO> loadUnlocked(String sessionId) {
        try {
            byte[] bytes = rustFsService.getChatJsonOrNull(objectKey(sessionId));
            if (bytes == null || bytes.length == 0) {
                return new ArrayList<>();
            }
            return MAPPER.readValue(bytes, LIST_TYPE);
        } catch (Exception e) {
            log.warn("[ChatMemory.RustFs] 读取失败 sid={}: {}", sessionId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private static String objectKey(String sessionId) {
        if (sessionId == null || sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("invalid sessionId");
        }
        return sessionId + ".json";
    }

    private ReentrantLock lockOf(String sessionId) {
        return locks.computeIfAbsent(sessionId, k -> new ReentrantLock());
    }

    private static String firstUserPreview(List<ChatMessageDTO> messages) {
        for (ChatMessageDTO m : messages) {
            if (m != null && m.getRole() != null && "user".equalsIgnoreCase(m.getRole())
                    && m.getContent() != null && !m.getContent().isBlank()) {
                return m.getContent();
            }
        }
        return null;
    }
}
