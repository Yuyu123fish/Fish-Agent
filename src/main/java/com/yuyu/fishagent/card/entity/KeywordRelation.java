package com.yuyu.fishagent.card.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关键词间语义关系：同义、上下位和相关关系，供关联发现做扩展匹配。
 */
@Data
@TableName("keyword_relation")
public class KeywordRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("from_keyword_id")
    private Long fromKeywordId;

    @TableField("to_keyword_id")
    private Long toKeywordId;

    @TableField("relation_type")
    private String relationType;

    private Float confidence;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
