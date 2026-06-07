package com.yuyu.fishagent.card.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 卡片-关键词关联表，作为 keywords JSON 的归一化索引补充。
 */
@Data
@TableName("card_keyword")
public class CardKeyword {

    public static final String SOURCE_AI = "ai";
    public static final String SOURCE_MANUAL = "manual";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("card_id")
    private Long cardId;

    @TableField("keyword_id")
    private Long keywordId;

    private String source;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
