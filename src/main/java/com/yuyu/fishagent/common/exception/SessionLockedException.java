package com.yuyu.fishagent.common.exception;

/**
 * 同一会话已有流式对话在进行中，拒绝并发进入 Agent 主循环。
 * <p>由 {@link com.yuyu.fishagent.chat.ChatService#streamChat} 在获取 Redis 会话锁失败时抛出，
 * 由 {@link GlobalExceptionHandler} 映射为 HTTP 409。</p>
 */
public class SessionLockedException extends RuntimeException {

    public SessionLockedException(String message) {
        super(message);
    }
}
