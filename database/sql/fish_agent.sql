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

  -- ============================================================
-- 知识卡片功能 - MySQL 建表
-- 执行方式：在 Fish Agent 对应的 MySQL 数据库中执行
-- ============================================================

-- 卡片主表
CREATE TABLE IF NOT EXISTS knowledge_card (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT NOT NULL,
  title       VARCHAR(200) NOT NULL,
  content     TEXT NOT NULL,
  keywords    JSON COMMENT '关键词数组，如 ["JVM","内存","GC"]',
  card_type   VARCHAR(20) NOT NULL DEFAULT 'concept' COMMENT 'concept(概念) / topic(主题)',
  source_type VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT 'chat / manual / knowledge',
  source_id   VARCHAR(100) COMMENT 'sessionId 或 documentId，NULL 为手动创建',
  status      VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending / confirmed / rejected',
  group_name  VARCHAR(100) COMMENT '分组名称，NULL 表示未分组',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_user_status (user_id, status),
  INDEX idx_source (source_type, source_id),
  INDEX idx_user_group (user_id, group_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 卡片关联表
CREATE TABLE IF NOT EXISTS card_relation (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  from_card_id   BIGINT NOT NULL,
  to_card_id     BIGINT NOT NULL,
  relation_type  VARCHAR(30) NOT NULL COMMENT 'related_to / contains / precedes / derived_from',
  confidence     FLOAT NOT NULL DEFAULT 1.0 COMMENT 'AI 置信度 0~1，手动创建为 1.0',
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_from (from_card_id),
  INDEX idx_to (to_card_id),
  UNIQUE INDEX uk_relation (from_card_id, to_card_id, relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;