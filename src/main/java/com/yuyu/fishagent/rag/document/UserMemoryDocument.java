package com.yuyu.fishagent.rag.document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;

/**
 * 用户私有长期记忆文档（索引 fish-user-memory）。
 * <p>字段名与 ES mapping 对齐：{@code user_id}、{@code created_at}。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "fish-user-memory")
public class UserMemoryDocument {

    @Id
    private String id;

    @Field(name = "user_id", type = FieldType.Keyword)
    private String userId;

    private String content;

    @Field(name = "created_at")
    private long createdAt;

    private List<Float> embedding;

    @Field(name = "doc_id", type = FieldType.Keyword)
    private String docId;

    /** 知识库切片的文档名（fish-user-knowledge 索引有此字段）；记忆索引无此字段时为 null。 */
    @Field(name = "doc_name", type = FieldType.Keyword)
    private String docName;

    @Field(name = "source_type", type = FieldType.Keyword)
    private String sourceType;

    @Field(name = "page_number", type = FieldType.Integer)
    private Integer pageNumber;

    @Field(name = "chunk_index", type = FieldType.Integer)
    private Integer chunkIndex;

    @Field(name = "superseded", type = FieldType.Boolean)
    private boolean superseded = false;

    @Field(name = "valid_to")
    private Long validTo;

    @Field(name = "authority", type = FieldType.Double)
    private Double authority;

    @Field(name = "doc_created_at")
    private Long docCreatedAt;

    @Field(name = "context_prefix", type = FieldType.Text)
    private String contextPrefix;

    @Field(name = "contextualized_content", type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String contextualizedContent;

    public Long bestCreatedAt() {
        return docCreatedAt != null ? docCreatedAt : createdAt;
    }
}
