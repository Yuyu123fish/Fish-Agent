package com.yuyu.fishagent.enums;

/**
 * 系统角色枚举，与表字段 {@code sys_user.role} 对应。
 */
public enum UserRole {

    /** 普通用户。 */
    USER,
    /** 管理员（预留权限扩展）。 */
    ADMIN;

    /**
     * 将枚举转为存入数据库的大写字符串。
     *
     * @return 枚举名，如 {@code USER}
     */
    public String toDbValue() {
        return name();
    }

    /**
     * 从数据库字符串解析角色，未知值回落为 {@link #USER}。
     *
     * @param raw 数据库中的角色字面量
     * @return 解析后的枚举
     */
    public static UserRole fromDb(String raw) {
        if (raw == null || raw.isBlank()) {
            return USER;
        }
        try {
            return UserRole.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return USER;
        }
    }
}
