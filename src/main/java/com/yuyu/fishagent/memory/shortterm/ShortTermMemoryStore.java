package com.yuyu.fishagent.memory.shortterm;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;

import java.util.List;

/**
 * 短期记忆存储 SPI。
 * <p>上层只依赖接口，Redis 只是当前默认实现，后续可以替换成内存、数据库或其他缓存。</p>
 */
public interface ShortTermMemoryStore {

    /**
     * 保存某会话的短期记忆快照。
     *
     * @param sessionId 会话 ID
     * @param snapshot 短期记忆快照
     */
    void save(String sessionId, ShortTermMemorySnapshot snapshot);

    /**
     * 旧签名兼容入口：调用方逐步迁移期间仍可保存纯文本摘要。
     */
    default void save(String sessionId, String summary, List<ChatMessageDTO> recentMessages) {
        save(sessionId, new ShortTermMemorySnapshot(summary, recentMessages));
    }

    /**
     * 读取某会话的短期记忆快照。不存在或存储不可用时应返回空快照。
     *
     * @param sessionId 会话 ID
     * @return 短期摘要与最近消息窗口
     */
    ShortTermMemorySnapshot load(String sessionId);

    /**
     * 删除某会话的短期记忆（摘要 + 最近窗口）。存储不可用时应静默跳过。
     *
     * @param sessionId 会话 ID
     */
    void clear(String sessionId);
}
