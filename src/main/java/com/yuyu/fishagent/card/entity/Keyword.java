package com.yuyu.fishagent.card.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关键词实体表：把卡片 JSON keywords 归一化为可查询、可关联的结构化实体。
 */
@Data
@TableName("keyword")
public class Keyword {

    public static final String TYPE_SYNONYM = "synonym";
    public static final String TYPE_BROADER = "broader";
    public static final String TYPE_NARROWER = "narrower";
    public static final String TYPE_RELATED = "related_to";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    private String name;

    @TableField("normalized_name")
    private String normalizedName;

    private String category;

    @TableField("parent_id")
    private Long parentId;

    @TableField("card_count")
    private Integer cardCount;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
