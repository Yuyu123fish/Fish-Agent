package com.yuyu.fishagent.memory.shortterm;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;

import java.util.List;

/**
 * 短期记忆存储 SPI。
 * <p>上层只依赖接口，Redis 只是当前默认实现，后续可以替换成内存、数据库或其他缓存。</p>
 */
public interface ShortTermMemoryStore {

    /**
     * 保存某会话的短期摘要和最近消息窗口。
     *
     * @param sessionId 会话 ID
     * @param summary 压缩后的短期摘要
     * @param recentMessages 最近 N 条消息，用于下一轮组装模型上下文
     */
    void save(String sessionId, String summary, List<ChatMessageDTO> recentMessages);

    /**
     * 读取某会话的短期记忆快照。不存在或存储不可用时应返回空快照。
     *
     * @param sessionId 会话 ID
     * @return 短期摘要与最近消息窗口
     */
    ShortTermMemorySnapshot load(String sessionId);
}
