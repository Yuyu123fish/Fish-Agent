package com.yuyu.fishagent.rag.tracing;

import com.yuyu.fishagent.common.trace.MdcAsync;
import com.yuyu.fishagent.rag.config.RagProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * RAG 检索质量日志写入器。
 * <p>采样、异步写入和失败降级都收敛在这里，召回编排只负责上报指标对象。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagQualityLogger {

    private final ObjectProvider<ElasticsearchOperations> operationsProvider;
    private final RagProperties ragProperties;

    @PostConstruct
    public void initIndex() {
        RagProperties.Tracing cfg = ragProperties.getTracing();
        if (!cfg.isEnabled()) {
            return;
        }
        ElasticsearchOperations operations = operationsProvider.getIfAvailable();
        if (operations == null) {
            log.debug("[RagQualityLogger] ElasticsearchOperations 不可用，跳过追踪索引初始化");
            return;
        }
        try {
            IndexOperations indexOps = operations.indexOps(IndexCoordinates.of(cfg.getIndexName()));
            if (!indexOps.exists()) {
                indexOps.create();
                indexOps.putMapping(traceMapping());
                log.info("[RagQualityLogger] 已创建 RAG 追踪索引: {}", cfg.getIndexName());
            }
        } catch (Exception e) {
            log.warn("[RagQualityLogger] 初始化追踪索引失败: {}", e.getMessage());
        }
    }

    public void log(RagTraceDocument trace) {
        RagProperties.Tracing cfg = ragProperties.getTracing();
        if (!cfg.isEnabled() || trace == null) {
            return;
        }
        double sampleRate = Math.max(0.0, Math.min(1.0, cfg.getSampleRate()));
        if (sampleRate < 1.0 && ThreadLocalRandom.current().nextDouble() >= sampleRate) {
            return;
        }
        if (cfg.isAsync()) {
            MdcAsync.mdcRunAsync(() -> doSave(cfg.getIndexName(), trace));
        } else {
            doSave(cfg.getIndexName(), trace);
        }
    }

    private void doSave(String indexName, RagTraceDocument trace) {
        try {
            ElasticsearchOperations operations = operationsProvider.getIfAvailable();
            if (operations == null) {
                return;
            }
            operations.save(trace, IndexCoordinates.of(indexName));
        } catch (Exception e) {
            log.warn("[RagQualityLogger] 写入 RAG 追踪日志失败 traceId={}: {}", trace.getTraceId(), e.getMessage());
        }
    }

    private Document traceMapping() {
        Document mapping = Document.create();
        mapping.put("properties", Map.ofEntries(
                Map.entry("trace_id", Map.of("type", "keyword")),
                Map.entry("user_id", Map.of("type", "keyword")),
                Map.entry("session_id", Map.of("type", "keyword")),
                Map.entry("original_query", Map.of("type", "text", "analyzer", "ik_max_word", "search_analyzer", "ik_smart")),
                Map.entry("rewritten_query", Map.of("type", "text", "analyzer", "ik_max_word", "search_analyzer", "ik_smart")),
                Map.entry("expanded_queries", Map.of("type", "text", "analyzer", "ik_max_word", "search_analyzer", "ik_smart")),
                Map.entry("recall_total_hits", Map.of("type", "integer")),
                Map.entry("recall_deduped_hits", Map.of("type", "integer")),
                Map.entry("fusion_top_n", Map.of("type", "integer")),
                Map.entry("rerank_input_count", Map.of("type", "integer")),
                Map.entry("rerank_top_score", Map.of("type", "double")),
                Map.entry("rerank_lowest_score", Map.of("type", "double")),
                Map.entry("hyde_used", Map.of("type", "boolean")),
                Map.entry("injected_fact_count", Map.of("type", "integer")),
                Map.entry("injected_total_chars", Map.of("type", "integer")),
                Map.entry("recall_latency_ms", Map.of("type", "long")),
                Map.entry("rerank_latency_ms", Map.of("type", "long")),
                Map.entry("total_latency_ms", Map.of("type", "long")),
                Map.entry("created_at", Map.of("type", "date", "format", "epoch_millis"))
        ));
        return mapping;
    }
}
