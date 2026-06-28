package com.yuyu.fishagent.rag.tracing;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;

/**
 * RAG 检索全链路质量日志文档。
 * <p>字段名与 ES mapping 对齐；实际索引名由保存时的 IndexCoordinates 决定。</p>
 */
@Data
@Document(indexName = "fish-rag-trace")
public class RagTraceDocument {

    @Id
    @Field(name = "trace_id", type = FieldType.Keyword)
    private String traceId;

    @Field(name = "user_id", type = FieldType.Keyword)
    private String userId;

    @Field(name = "session_id", type = FieldType.Keyword)
    private String sessionId;

    @Field(name = "original_query", type = FieldType.Text)
    private String originalQuery;

    @Field(name = "rewritten_query", type = FieldType.Text)
    private String rewrittenQuery;

    @Field(name = "expanded_queries", type = FieldType.Text)
    private List<String> expandedQueries;

    @Field(name = "recall_total_hits", type = FieldType.Integer)
    private int recallTotalHits;

    @Field(name = "recall_deduped_hits", type = FieldType.Integer)
    private int recallDedupedHits;

    @Field(name = "fusion_top_n", type = FieldType.Integer)
    private int fusionTopN;

    @Field(name = "rerank_input_count", type = FieldType.Integer)
    private int rerankInputCount;

    @Field(name = "rerank_top_score", type = FieldType.Double)
    private double rerankTopScore;

    @Field(name = "rerank_lowest_score", type = FieldType.Double)
    private double rerankLowestScore;

    @Field(name = "hyde_used", type = FieldType.Boolean)
    private boolean hydeUsed;

    @Field(name = "injected_fact_count", type = FieldType.Integer)
    private int injectedFactCount;

    @Field(name = "injected_total_chars", type = FieldType.Integer)
    private int injectedTotalChars;

    @Field(name = "recall_latency_ms", type = FieldType.Long)
    private long recallLatencyMs;

    @Field(name = "rerank_latency_ms", type = FieldType.Long)
    private long rerankLatencyMs;

    @Field(name = "provenance_latency_ms", type = FieldType.Long)
    private long provenanceLatencyMs;

    @Field(name = "expand_latency_ms", type = FieldType.Long)
    private long expandLatencyMs;

    @Field(name = "total_latency_ms", type = FieldType.Long)
    private long totalLatencyMs;

    @Field(name = "created_at")
    private long createdAt;

    /** 本轮真正注入上下文的事实明细（id / 来源标签 / 分数），用于 per-fact 可观测与离线评估。 */
    @Field(name = "injected_facts", type = FieldType.Object)
    private List<InjectedFact> injectedFacts;

    /** 单条注入事实的溯源记录。 */
    @Data
    public static class InjectedFact {
        @Field(name = "id", type = FieldType.Keyword)
        private String id;

        @Field(name = "source_label", type = FieldType.Keyword)
        private String sourceLabel;

        @Field(name = "score", type = FieldType.Double)
        private Double score;
    }
}
