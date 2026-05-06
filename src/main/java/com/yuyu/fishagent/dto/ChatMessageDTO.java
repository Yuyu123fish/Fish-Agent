package com.yuyu.fishagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话消息持久化 DTO（与前端展示模型一致）。
 * <p>{@code role} 取值：{@code user} / {@code assistant} / {@code system} / {@code tool}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDTO {

    private String role;

    private String content;

    private long createdAt;

    public static ChatMessageDTO of(String role, String content) {
        return new ChatMessageDTO(role, content, System.currentTimeMillis());
    }
}
