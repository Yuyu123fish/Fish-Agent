package com.yuyu.fishagent.dto;

import lombok.Data;

/**
 * 聊天请求体：仅包含 sessionId 与本轮用户消息。历史由后端按 sessionId 加载。
 */
@Data
public class ChatRequest {

    /**
     * 会话 ID。前端首次对话不传时由后端生成；后续轮次携带同一 sessionId 即可保持上下文。
     */
    private String sessionId;

    /**
     * 当前用户输入。
     */
    private String message;
}
