package com.yuyu.fishagent.chat.history;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.chat.dto.SessionInfo;

import java.util.List;

/**
 * 原始对话历史存储 SPI。
 * <p>只负责会话消息的持久化与列表展示，不承载摘要、向量或召回逻辑。</p>
 */
public interface ChatMemoryStore {

    /**
     * 加载某会话的全部历史消息（按时间正序）。会话不存在时返回空列表。
     */
    List<ChatMessageDTO> load(String sessionId);

    /**
     * 追加一条消息到会话尾部并落盘。
     */
    void append(String sessionId, ChatMessageDTO message);

    /**
     * 批量追加（同步落盘一次，减少 IO）。
     */
    void appendAll(String sessionId, List<ChatMessageDTO> messages);

    /**
     * 删除某会话的全部历史。
     */
    void clear(String sessionId);

    /**
     * 列出所有会话摘要，按 updatedAt 倒序。
     */
    List<SessionInfo> listSessions();
}
