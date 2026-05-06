package com.yuyu.fishagent.auth.context;

/**
 * 基于 ThreadLocal 的请求级用户上下文；必须在请求结束时 {@link #clear()}，防止线程池复用泄露。
 */
public final class UserContextHolder {

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    private UserContextHolder() {
    }

    /**
     * 绑定当前线程的用户上下文。
     *
     * @param ctx 用户上下文，允许 {@code null} 表示匿名（不推荐在生产路径使用）
     */
    public static void set(UserContext ctx) {
        HOLDER.set(ctx);
    }

    /**
     * @return 当前线程上下文；未登录处理前可能为 {@code null}
     */
    public static UserContext get() {
        return HOLDER.get();
    }

    /**
     * @return 当前用户 ID；未登录时返回 {@code null}
     */
    public static Long currentUserIdOrNull() {
        UserContext c = HOLDER.get();
        return c == null ? null : c.userId();
    }

    /**
     * 移除线程绑定，避免容器线程复用导致串用户。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
