package com.yuyu.fishagent.memory.shortterm;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.memory.MemoryCompressionService;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.memory.dto.MemoryCompressionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 短期记忆三级协调器：编排 L1(Redis) → L2(对象存储快照) → L3(全量历史) 的读穿与写穿。
 * <ul>
 *   <li>{@link #loadForTurn} 读穿：L1 命中直接用；否则 L2 回填 L1；再否则冷会话读 L3，必要时同步重算摘要。</li>
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
     * 读穿：返回本轮对话使用的短期记忆快照。运行在 Servlet 线程（UserContext 可用）。
     *
     * @param userId            会话所属用户（供 L2 文件实现分区）
     * @param sessionId         会话 ID
     * @param fullHistoryLoader 冷会话时才会被调用的 L3 全量历史加载器
     */
    public ShortTermMemorySnapshot loadForTurn(Long userId, String sessionId,
                                               Supplier<List<ChatMessageDTO>> fullHistoryLoader) {
        ShortTermMemorySnapshot l1Snap = safeSnapshot(l1.load(sessionId));
        if (isNonEmpty(l1Snap)) {
            return l1Snap;
        }

        if (properties.getSnapshot().isEnabled()) {
            ShortTermMemorySnapshot l2Snap = safeSnapshot(l2.load(userId, sessionId));
            if (isNonEmpty(l2Snap)) {
                l1.save(sessionId, safeSummary(l2Snap), safeMessages(l2Snap));
                log.debug("[ShortTermMemoryService] L2 命中并回填 L1 sid={}", sessionId);
                return l2Snap;
            }
        }

        List<ChatMessageDTO> full = fullHistoryLoader.get();
        if (full == null || full.isEmpty()) {
            return empty();
        }

        if (properties.getSnapshot().isRecomputeOnCold()
                && full.size() >= properties.getSummaryTriggerThreshold()) {
            try {
                compression.compress(new MemoryCompressionRequest(sessionId, full));
                ShortTermMemorySnapshot recomputed = safeSnapshot(l1.load(sessionId));
                if (properties.getSnapshot().isEnabled() && isNonEmpty(recomputed)) {
                    l2.save(userId, sessionId, recomputed);
                }
                log.debug("[ShortTermMemoryService] 冷会话同步重算完成 sid={}, historySize={}", sessionId, full.size());
                return recomputed;
            } catch (Exception e) {
                log.warn("[ShortTermMemoryService] 冷会话重算失败，降级为窗口 sid={}: {}", sessionId, e.getMessage());
            }
        }

        List<ChatMessageDTO> window = tail(full, properties.getShortTermWindowSize());
        ShortTermMemorySnapshot snapshot = new ShortTermMemorySnapshot("", window);
        l1.save(sessionId, "", window);
        if (properties.getSnapshot().isEnabled()) {
            l2.save(userId, sessionId, snapshot);
        }
        log.debug("[ShortTermMemoryService] 冷会话窗口降级 sid={}, windowSize={}", sessionId, window.size());
        return snapshot;
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
        l1.save(sessionId, safeSummary(current), tail(window, properties.getShortTermWindowSize()));
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

    private static boolean isNonEmpty(ShortTermMemorySnapshot snapshot) {
        boolean hasSummary = snapshot.summary() != null && !snapshot.summary().isBlank();
        boolean hasWindow = snapshot.recentMessages() != null && !snapshot.recentMessages().isEmpty();
        return hasSummary || hasWindow;
    }

    private static ShortTermMemorySnapshot safeSnapshot(ShortTermMemorySnapshot snapshot) {
        return snapshot == null ? empty() : snapshot;
    }

    private static String safeSummary(ShortTermMemorySnapshot snapshot) {
        return snapshot.summary() == null ? "" : snapshot.summary();
    }

    private static List<ChatMessageDTO> safeMessages(ShortTermMemorySnapshot snapshot) {
        return snapshot.recentMessages() == null ? List.of() : snapshot.recentMessages();
    }

    private static ShortTermMemorySnapshot empty() {
        return new ShortTermMemorySnapshot("", List.of());
    }

    private static List<ChatMessageDTO> tail(List<ChatMessageDTO> list, int windowSize) {
        if (list == null || list.isEmpty() || windowSize <= 0) {
            return List.of();
        }
        int fromIndex = Math.max(0, list.size() - windowSize);
        return new ArrayList<>(list.subList(fromIndex, list.size()));
    }
}
