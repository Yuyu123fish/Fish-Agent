package com.yuyu.fishagent.agent.memory.shortterm;

import com.yuyu.fishagent.dto.ChatMessageDTO;

import java.util.List;

/**
 * 短期记忆快照：摘要 + 最近消息窗口。
 */
public record ShortTermMemorySnapshot(String summary, List<ChatMessageDTO> recentMessages) {
}
