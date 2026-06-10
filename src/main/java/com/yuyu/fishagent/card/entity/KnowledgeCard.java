package com.yuyu.fishagent.card.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识卡片主表实体，手动卡片、AI 提取卡片与知识库来源卡片共用这一张表。
 */
@Data
@TableName(value = "knowledge_card", autoResultMap = true)
public class KnowledgeCard {

    public static final String TYPE_CONCEPT = "concept";
    public static final String TYPE_TOPIC = "topic";

    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_CHAT = "chat";
    public static final String SOURCE_KNOWLEDGE = "knowledge";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_REJECTED = "rejected";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    private String title;

    private String content;

    /** MySQL JSON 数组，业务层保持 List<String>，避免散落手工 JSON 解析。 */
    @TableField(value = "keywords", typeHandler = JacksonTypeHandler.class)
    private List<String> keywords = new ArrayList<>();

    @TableField("card_type")
    private String cardType;

    @TableField("source_type")
    private String sourceType;

    @TableField("source_id")
    private String sourceId;

    private String status;

    @TableField("group_name")
    private String groupName;

    @TableField("group_id")
    private Long groupId;

    @TableField("review_next_at")
    private LocalDateTime reviewNextAt;

    @TableField("review_count")
    private Integer reviewCount = 0;

    @TableField("last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
