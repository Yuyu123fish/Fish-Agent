package com.yuyu.fishagent.agent.memory.history;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.config.AgentProperties;
import com.yuyu.fishagent.dto.ChatMessageDTO;
import com.yuyu.fishagent.dto.SessionInfo;
import com.yuyu.fishagent.service.ChatMetadataService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 用户分区文件历史：{@code history-dir/{userId}/{sessionId}.json}，列表来自 {@link ChatMetadataService}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fish.rustfs", name = "enabled", havingValue = "false")
public class UserScopedFileChatMemoryStore implements ChatMemoryStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ChatMessageDTO>> LIST_TYPE = new TypeReference<>() {};

    private final AgentProperties properties;
    private final ChatMetadataService chatMetadataService;

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private Path baseDir;

    /**
     * 初始化根目录（history-dir）。
     */
    @PostConstruct
    public void init() throws IOException {
        this.baseDir = Path.of(properties.getHistoryDir()).toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        log.info("[ChatMemory] 用户分区文件存储根目录: {}", baseDir);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ChatMessageDTO> load(String sessionId) {
        // 首次进入的新会话尚无 chat_metadata 行，允许返回空列表
        chatMetadataService.assertReadableSessionOrNew(sessionId);
        Path file = sessionFile(sessionId);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        ReentrantLock lock = lockOf(sessionId);
        lock.lock();
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return new ArrayList<>();
            }
            return MAPPER.readValue(bytes, LIST_TYPE);
        } catch (IOException e) {
            log.warn("[ChatMemory] 读取会话 {} 失败: {}", sessionId, e.getMessage());
            return new ArrayList<>();
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
            writeAtomically(sessionId, all);
            chatMetadataService.touchUpdatedAt(sessionId);
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
            Files.deleteIfExists(sessionFile(sessionId));
        } catch (IOException e) {
            log.warn("[ChatMemory] 删除会话 {} 失败: {}", sessionId, e.getMessage());
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
        Path file = sessionFile(sessionId);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return new ArrayList<>();
            }
            return MAPPER.readValue(bytes, LIST_TYPE);
        } catch (IOException e) {
            log.warn("[ChatMemory] 读取会话 {} 失败: {}", sessionId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeAtomically(String sessionId, List<ChatMessageDTO> all) {
        Path file = sessionFile(sessionId);
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            byte[] data = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(all);
            Files.write(tmp, data);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignore) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[ChatMemory] 写入会话 {} 失败: {}", sessionId, e.getMessage(), e);
        }
    }

    private Path sessionFile(String sessionId) {
        validateSessionId(sessionId);
        Long uid = UserContextHolder.currentUserIdOrNull();
        if (uid == null) {
            throw new IllegalStateException("no authenticated user");
        }
        return baseDir.resolve(String.valueOf(uid)).resolve(sessionId + ".json");
    }

    private ReentrantLock lockOf(String sessionId) {
        Long uid = UserContextHolder.currentUserIdOrNull();
        String key = uid + ":" + sessionId;
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    private static void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("invalid sessionId: " + sessionId);
        }
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
