package com.yuyu.fishagent.memory.shortterm;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.memory.MemoryCompressionService;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 短期记忆三级协调器：编排 L1(Redis) → L2(对象存储快照) → L3(全量历史) 的读穿与写穿。
 * <ul>
 *   <li>{@link #loadForTurn} 读穿：L1 命中直接用；否则 L2 回填 L1；再否则冷会话读 L3 并先降级返回窗口。</li>
 *   <li>{@link #appendTurnToL1} 同步把本轮消息追加进 L1 窗口（保证下一轮热路径可见）。</li>
 *   <li>{@link #refreshSnapshotFromL1} 把 L1 现状拷入 L2 快照（异步调用）。</li>
 *   <li>{@link #clear} 删除 L1 + L2。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortTermMemoryService {

    private final ShortTermMemoryStore l1;
    private final ShortTermSnapshotStore l2;
    private final MemoryCompressionService compression;
    private final MemoryProperties properties;

    /**
     * 本轮短期记忆加载结果。
     *
     * @param snapshot             可直接注入模型上下文的短期记忆快照
     * @param compressedOnColdPath 本轮是否已在冷路径同步重算过摘要
     */
    public record ShortTermMemoryLoadResult(ShortTermMemorySnapshot snapshot, boolean compressedOnColdPath) {
    }

    /**
     * 读穿：返回本轮对话使用的短期记忆快照。运行在 Servlet 线程（UserContext 可用）。
     *
     * @param userId            会话所属用户（供 L2 文件实现分区）
     * @param sessionId         会话 ID
     * @param fullHistoryLoader 冷会话时才会被调用的 L3 全量历史加载器
     */
    public ShortTermMemorySnapshot loadForTurn(Long userId, String sessionId,
                                               Supplier<List<ChatMessageDTO>> fullHistoryLoader) {
        return loadForTurnWithMetadata(userId, sessionId, fullHistoryLoader).snapshot();
    }

    /**
     * 读穿并返回本轮加载元信息。ChatService 用 {@code compressedOnColdPath} 避免冷会话首轮重复压缩。
     *
     * @param userId            会话所属用户（供 L2 文件实现分区）
     * @param sessionId         会话 ID
     * @param fullHistoryLoader 冷会话时才会被调用的 L3 全量历史加载器
     */
    public ShortTermMemoryLoadResult loadForTurnWithMetadata(Long userId, String sessionId,
                                                             Supplier<List<ChatMessageDTO>> fullHistoryLoader) {
        ShortTermMemorySnapshot l1Snap = safeSnapshot(l1.load(sessionId));
        if (isNonEmpty(l1Snap)) {
            return new ShortTermMemoryLoadResult(l1Snap, false);
        }

        if (properties.getSnapshot().isEnabled()) {
            ShortTermMemorySnapshot l2Snap = safeSnapshot(l2.load(userId, sessionId));
            if (isNonEmpty(l2Snap)) {
                l1.save(sessionId, l2Snap);
                log.debug("[ShortTermMemoryService] L2 命中并回填 L1 sid={}", sessionId);
                return new ShortTermMemoryLoadResult(l2Snap, false);
            }
        }

        List<ChatMessageDTO> full = fullHistoryLoader.get();
        if (full == null || full.isEmpty()) {
            return new ShortTermMemoryLoadResult(empty(), false);
        }

        List<ChatMessageDTO> window = tail(full, properties.getShortTermWindowSize());
        ShortTermMemorySnapshot snapshot = new ShortTermMemorySnapshot("", window);
        l1.save(sessionId, snapshot);
        if (properties.getSnapshot().isEnabled()) {
            l2.save(userId, sessionId, snapshot);
        }
        log.debug("[ShortTermMemoryService] 冷会话窗口降级 sid={}, windowSize={}，异步压缩将在 onComplete 维护任务中触发",
                sessionId, window.size());
        return new ShortTermMemoryLoadResult(snapshot, false);
    }

    /**
     * 同步把本轮 user/assistant 追加进 L1 窗口并裁剪到窗口大小。摘要保持不变。
     */
    public void appendTurnToL1(String sessionId, ChatMessageDTO userMsg, ChatMessageDTO assistantMsg) {
        ShortTermMemorySnapshot current = safeSnapshot(l1.load(sessionId));
        List<ChatMessageDTO> window = new ArrayList<>(safeMessages(current));
        if (userMsg != null) {
            window.add(userMsg);
        }
        if (assistantMsg != null) {
            window.add(assistantMsg);
        }
        ShortTermMemorySnapshot updated = new ShortTermMemorySnapshot(
                current.structuredSummary(),
                tail(window, properties.getShortTermWindowSize()),
                current.keyExcerpts(),
                current.incrementalCount(),
                current.lastCompressedAt(),
                current.lastCompressedMessageCount()
        );
        l1.save(sessionId, updated);
    }

    /**
     * 把 L1 现状（摘要 + 窗口）拷入 L2 快照。异步调用，失败仅记日志。
     */
    public void refreshSnapshotFromL1(Long userId, String sessionId) {
        if (!properties.getSnapshot().isEnabled()) {
            return;
        }
        ShortTermMemorySnapshot snapshot = safeSnapshot(l1.load(sessionId));
        if (!isNonEmpty(snapshot)) {
            return;
        }
        l2.save(userId, sessionId, snapshot);
    }

    /**
     * 判断异步维护是否值得回源 L3 全量历史。
     * <p>没有摘要且 L1 窗口还远低于压缩阈值时，跳过 L3 全量读，只刷新 L2 快照即可。</p>
     */
    public boolean shouldLoadFullHistoryForMaintenance(String sessionId) {
        ShortTermMemorySnapshot snapshot = safeSnapshot(l1.load(sessionId));
        if (snapshot.structuredSummary() != null) {
            if (snapshot.lastCompressedAt() <= 0) {
                return true;
            }
            return countMessagesAfter(safeMessages(snapshot), snapshot.lastCompressedAt()) >= maintenanceTriggerSize();
        }
        return safeMessages(snapshot).size() >= maintenanceTriggerSize();
    }

    /**
     * 删除 L1 + L2 短期记忆。
     */
    public void clear(Long userId, String sessionId) {
        try {
            l1.clear(sessionId);
        } catch (Exception e) {
            log.warn("[ShortTermMemoryService] 清理 L1 失败 sid={}: {}", sessionId, e.getMessage());
        }
        if (properties.getSnapshot().isEnabled()) {
            try {
                l2.clear(userId, sessionId);
            } catch (Exception e) {
                log.warn("[ShortTermMemoryService] 清理 L2 失败 sid={}: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 获取当前 L1 快照，供异步维护判断增量/校准模式。
     */
    public ShortTermMemorySnapshot getCurrentSnapshot(String sessionId) {
        return safeSnapshot(l1.load(sessionId));
    }

    private static boolean isNonEmpty(ShortTermMemorySnapshot snapshot) {
        boolean hasSummary = snapshot.structuredSummary() != null;
        boolean hasWindow = snapshot.recentMessages() != null && !snapshot.recentMessages().isEmpty();
        return hasSummary || hasWindow;
    }

    private static ShortTermMemorySnapshot safeSnapshot(ShortTermMemorySnapshot snapshot) {
        return snapshot == null ? empty() : snapshot;
    }

    private static List<ChatMessageDTO> safeMessages(ShortTermMemorySnapshot snapshot) {
        return snapshot.recentMessages() == null ? List.of() : snapshot.recentMessages();
    }

    private static long countMessagesAfter(List<ChatMessageDTO> messages, long timestamp) {
        return messages.stream()
                .filter(message -> message != null && message.getCreatedAt() > timestamp)
                .count();
    }

    private int maintenanceTriggerSize() {
        int halfThreshold = Math.max(1, properties.getSummaryTriggerThreshold() / 2);
        int windowSize = Math.max(1, properties.getShortTermWindowSize());
        return Math.min(halfThreshold, windowSize);
    }

    private static ShortTermMemorySnapshot empty() {
        return new ShortTermMemorySnapshot(null, List.of(), List.of(), 0);
    }

    private static List<ChatMessageDTO> tail(List<ChatMessageDTO> list, int windowSize) {
        if (list == null || list.isEmpty() || windowSize <= 0) {
            return List.of();
        }
        int fromIndex = Math.max(0, list.size() - windowSize);
        return new ArrayList<>(list.subList(fromIndex, list.size()));
    }
}
