package com.yuyu.fishagent.memory.agentstate;

/**
 * Agent 状态存储 SPI，独立于短期记忆 L1/L2 链路。
 */
public interface AgentStateStore {

    /**
     * 加载会话 Agent 状态。不存在或存储不可用时返回 IDLE 空状态。
     */
    SessionAgentState load(Long userId, String sessionId);

    /**
     * 保存会话 Agent 状态。失败时记录日志，不影响聊天主链路。
     */
    void save(Long userId, String sessionId, SessionAgentState state);

    /**
     * 删除会话 Agent 状态。失败时记录日志，不影响删除主链路。
     */
    void clear(Long userId, String sessionId);
}
