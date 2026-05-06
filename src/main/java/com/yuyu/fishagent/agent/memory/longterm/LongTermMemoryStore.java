package com.yuyu.fishagent.agent.memory.longterm;

import java.util.List;

/**
 * 长期记忆存储 SPI。
 * <p>当前只定义“事实写入”能力，检索能力后续接入聊天流程时再扩展接口。</p>
 */
public interface LongTermMemoryStore {

    /**
     * 保存模型提取出的长期事实（写入用户隔离索引）。
     *
     * @param userId    归属用户；匿名上下文可为 {@code null}（实现层选择跳过写入）
     * @param sessionId 事实来源会话 ID（冗余留存便于运维追溯）
     * @param facts     长期高价值事实，调用方已过滤空数组
     */
    void saveFacts(Long userId, String sessionId, List<String> facts);
}
