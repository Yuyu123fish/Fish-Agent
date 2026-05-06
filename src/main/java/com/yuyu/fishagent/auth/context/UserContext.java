package com.yuyu.fishagent.auth.context;

/**
 * 当前登录用户上下文，存入 Redis 会话并在请求线程写入 {@link UserContextHolder}。
 *
 * @param userId   用户主键
 * @param username 登录账号
 * @param nickname 展示昵称
 * @param role     角色枚举名（如 USER）
 */
public record UserContext(Long userId, String username, String nickname, String role) {
}
