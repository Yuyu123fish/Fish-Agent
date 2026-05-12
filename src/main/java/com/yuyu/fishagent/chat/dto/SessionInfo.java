package com.yuyu.fishagent.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话摘要：用于会话列表展示。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionInfo {

    private String sessionId;

    /** 第一条用户消息（或自定义标题）截断后的预览。 */
    private String title;

    private int messageCount;

    private long updatedAt;
}
