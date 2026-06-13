package com.yuyu.fishagent.rag.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;

/**
 * 知识库切片 ES 文档。
 *
 * <p>私有索引 fish-user-knowledge 与公共索引 fish-public-knowledge 字段基本一致；
 * 查询时通过 {@link org.springframework.data.elasticsearch.core.mapping.IndexCoordinates} 指定实际索引。</p>
 */
@Data
@Document(indexName = "fish-user-knowledge")
public class KnowledgeChunkDocument {

    @Id
    private String id;

    @Field(name = "user_id", type = FieldType.Keyword)
    private String userId;

    @Field(name = "doc_id", type = FieldType.Keyword)
    private String docId;

    @Field(name = "doc_name", type = FieldType.Keyword)
    private String docName;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    private List<Float> embedding;

    @Field(name = "page_number", type = FieldType.Integer)
    private Integer pageNumber;

    @Field(name = "chunk_index", type = FieldType.Integer)
    private Integer chunkIndex;

    @Field(name = "file_type", type = FieldType.Keyword)
    private String fileType;

    @Field(name = "token_count", type = FieldType.Integer)
    private Integer tokenCount;

    @Field(name = "authority", type = FieldType.Double)
    private Double authority;

    @Field(name = "doc_created_at")
    private Long docCreatedAt;

    @Field(name = "context_prefix", type = FieldType.Text)
    private String contextPrefix;

    @Field(name = "contextualized_content", type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String contextualizedContent;
}
