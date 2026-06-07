package com.yuyu.fishagent.card.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * fish-knowledge-card ES 文档。confirmed 卡片写入文本字段和向量字段，供关联发现与后续 RAG 使用。
 */
@Data
@Document(indexName = "fish-knowledge-card")
public class KnowledgeCardDocument {

    @Id
    private String id;

    @Field(type = FieldType.Long)
    private Long cardId;

    @Field(type = FieldType.Long)
    private Long userId;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private List<String> keywords;

    @Field(type = FieldType.Keyword)
    private String cardType;

    @Field(type = FieldType.Keyword)
    private String sourceType;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Keyword)
    private String groupName;

    /** dense_vector 字段由 ES mapping 控制，Spring Data 这里只保留业务数据结构。 */
    private List<Float> embedding;

    @Field(type = FieldType.Date, format = {}, pattern = "yyyy-MM-dd'T'HH:mm:ss||epoch_millis")
    private String createdAt;

    private static final DateTimeFormatter ES_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 设置 createdAt，自动将 LocalDateTime 转为 ES 兼容的 ISO 字符串。 */
    public void setCreatedAt(LocalDateTime value) {
        this.createdAt = value == null ? null : value.format(ES_FMT);
    }
}
