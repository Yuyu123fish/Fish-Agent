package com.yuyu.fishagent.card.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 卡片分组实体：支持层级树结构，归一化去重，替代 knowledge_card.group_name 的扁平字符串。
 */
@Data
@TableName("card_group")
public class CardGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    private String name;

    @TableField("normalized_name")
    private String normalizedName;

    @TableField("parent_id")
    private Long parentId;

    @TableField("card_count")
    private Integer cardCount;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
