CREATE DATABASE IF NOT EXISTS fish_agent DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE fish_agent;

CREATE TABLE IF NOT EXISTS `sys_user`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `nickname`   VARCHAR(64)  NOT NULL COMMENT '昵称',
    `username`   VARCHAR(64)  NOT NULL COMMENT '账号（唯一）',
    `password`   VARCHAR(255) NOT NULL COMMENT 'BCrypt 加密密码',
    `role`       VARCHAR(32)  NOT NULL DEFAULT 'USER' COMMENT '角色：USER / ADMIN',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='系统用户表';

CREATE TABLE IF NOT EXISTS `chat_metadata`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`    BIGINT       NOT NULL COMMENT '关联 sys_user.id',
    `title`      VARCHAR(255)          DEFAULT NULL COMMENT '会话标题（大模型生成，用户可修改）',
    `session_id` VARCHAR(128) NOT NULL COMMENT '会话唯一标识 = RustFS 对象 Key',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='会话元数据表';

CREATE TABLE IF NOT EXISTS `document_metadata`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_id`    VARCHAR(64)  NOT NULL COMMENT '全局唯一追踪 ID（UUID），Redis Stream 消息与前端进度查询的凭证',
    `user_id`    BIGINT       NOT NULL COMMENT '关联 sys_user.id，上传者',
    `file_name`  VARCHAR(255) NOT NULL COMMENT '用户上传的原始文件名，用于前端文档列表展示',
    `file_size`  BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    `minio_path` VARCHAR(512) NOT NULL COMMENT 'RustFS/MinIO fish-docs 桶内精确存储路径，供 Python Worker 下载原文件',
    `scope_type` VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE' COMMENT '作用域：PRIVATE（写入 fish-user-memory）/ PUBLIC（写入 fish-public-knowledge）',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态机：PENDING / PROCESSING / SUCCESS / FAILED',
    `error_msg`   TEXT                  DEFAULT NULL COMMENT '解析失败时的错误详情或成功时的警告（如无可提取文本），供前端展示兜底提示',
    `chunk_count` INT                   DEFAULT NULL COMMENT 'Python Worker 成功写入 ES 的切片数量',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='知识库文件上传任务元数据表';