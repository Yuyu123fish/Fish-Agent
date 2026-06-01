package com.yuyu.fishagent.memory.shortterm;

/**
 * 短期记忆 L2 快照存储 SPI（对象存储 / 文件兜底）。
 * <p>作为 Redis(L1) 失效时的兜源：持久保存 summary + 最近窗口，无 TTL。
 * 不存在或存储不可用时应返回空快照。</p>
 */
public interface ShortTermSnapshotStore {

    /**
     * 读取会话的短期记忆快照。不存在 / 不可用时返回空快照（不可返回 null）。
     *
     * @param userId    会话所属用户（文件实现据此分区；对象存储实现可忽略）
     * @param sessionId 会话 ID
     */
    ShortTermMemorySnapshot load(Long userId, String sessionId);

    /**
     * 覆盖写会话的短期记忆快照。失败时记录日志，不抛出。
     */
    void save(Long userId, String sessionId, ShortTermMemorySnapshot snapshot);

    /**
     * 删除会话的短期记忆快照。失败时记录日志，不抛出。
     */
    void clear(Long userId, String sessionId);
}
