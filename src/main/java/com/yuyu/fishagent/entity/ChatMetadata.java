package com.yuyu.fishagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话元数据，映射 {@code chat_metadata}；正文 JSON 在 RustFS 或用户分区文件中。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_metadata")
public class ChatMetadata {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    private String title;

    @TableField("session_id")
    private String sessionId;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
