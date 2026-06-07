package com.yuyu.fishagent.card.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识卡片之间的轻量关联，阶段 1 只预留展示，后续 AI 提取与图谱会写入更多关系。
 */
@Data
@TableName("card_relation")
public class CardRelation {

    public static final String TYPE_RELATED_TO = "related_to";
    public static final String TYPE_CONTAINS = "contains";
    public static final String TYPE_PRECEDES = "precedes";
    public static final String TYPE_DERIVED_FROM = "derived_from";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("from_card_id")
    private Long fromCardId;

    @TableField("to_card_id")
    private Long toCardId;

    @TableField("relation_type")
    private String relationType;

    private Float confidence;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
