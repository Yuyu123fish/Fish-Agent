package com.yuyu.fishagent.card.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 卡片复习记录：每次评分写一条快照，列表和详情通过 knowledge_card 上的冗余字段快速展示。
 */
@Data
@TableName("card_review_record")
public class CardReviewRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("card_id")
    private Long cardId;

    @TableField("user_id")
    private Long userId;

    private Integer quality;

    @TableField("easiness_factor")
    private Double easinessFactor;

    @TableField("`interval`")
    private Integer intervalDays;

    private Integer repetition;

    @TableField("reviewed_at")
    private LocalDateTime reviewedAt;

    @TableField("next_review_at")
    private LocalDateTime nextReviewAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
