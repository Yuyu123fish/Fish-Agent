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
 * 公有知识库切片文档（索引 fish-public-knowledge）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "fish-public-knowledge")
public class PublicKnowledgeDocument {

    @Id
    private String id;

    @Field(name = "doc_name", type = FieldType.Keyword)
    private String docName;

    private String content;

    private List<Float> embedding;

    @Field(name = "created_at")
    private Long createdAt;

    @Field(name = "doc_id", type = FieldType.Keyword)
    private String docId;

    @Field(name = "page_number", type = FieldType.Integer)
    private Integer pageNumber;

    @Field(name = "chunk_index", type = FieldType.Integer)
    private Integer chunkIndex;
}
