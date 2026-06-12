package com.yuyu.fishagent.memory.shortterm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.agent.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 基于本地文件的短期记忆 L2 快照兜底：{@code {historyDir}/{userId}/{sessionId}.stm.json}。
 * <p>仅在 {@code fish.rustfs.enabled=false} 时激活，与 {@code UserScopedFileChatMemoryStore} 同根目录。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fish.rustfs", name = "enabled", havingValue = "false")
public class FileShortTermSnapshotStore implements ShortTermSnapshotStore {

    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    private Path baseDir;

    @PostConstruct
    public void init() throws IOException {
        this.baseDir = Path.of(properties.getHistoryDir()).toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        log.info("[FileShortTermSnapshotStore] 短期记忆快照根目录: {}", baseDir);
    }

    @Override
    public ShortTermMemorySnapshot load(Long userId, String sessionId) {
        Path file = snapshotFile(userId, sessionId);
        if (file == null || !Files.exists(file)) {
            return empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return empty();
            }
            ShortTermMemorySnapshot snapshot = objectMapper.readValue(bytes, ShortTermMemorySnapshot.class);
            return snapshot == null ? empty() : snapshot;
        } catch (IOException e) {
            log.warn("[FileShortTermSnapshotStore] 读取快照失败 sid={}: {}", sessionId, e.getMessage());
            return empty();
        }
    }

    @Override
    public void save(Long userId, String sessionId, ShortTermMemorySnapshot snapshot) {
        Path file = snapshotFile(userId, sessionId);
        if (file == null) {
            log.warn("[FileShortTermSnapshotStore] 缺少 userId，跳过快照写入 sid={}", sessionId);
            return;
        }
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            byte[] data = objectMapper.writeValueAsBytes(snapshot == null ? empty() : snapshot);
            Files.write(tmp, data);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignore) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("[FileShortTermSnapshotStore] 写入快照失败 sid={}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void clear(Long userId, String sessionId) {
        Path file = snapshotFile(userId, sessionId);
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("[FileShortTermSnapshotStore] 删除快照失败 sid={}: {}", sessionId, e.getMessage());
        }
    }

    private Path snapshotFile(Long userId, String sessionId) {
        if (userId == null) {
            return null;
        }
        if (sessionId == null || sessionId.isBlank() || sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("invalid sessionId: " + sessionId);
        }
        return baseDir.resolve(String.valueOf(userId)).resolve(sessionId + ".stm.json");
    }

    private static ShortTermMemorySnapshot empty() {
        return new ShortTermMemorySnapshot(null, List.of(), List.of(), 0);
    }
}
