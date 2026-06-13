package com.yuyu.fishagent.common.trace;

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
 * TurnTrace 异步 ES 写入器。
 *
 * <p>写入失败只记录日志，不影响 SSE 主链路；采样和索引名也收敛在这里。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceEsWriter {

    private final ObjectProvider<ElasticsearchOperations> operationsProvider;
    private final TraceProperties properties;

    @PostConstruct
    public void initIndex() {
        if (!properties.isEnabled()) {
            return;
        }
        ElasticsearchOperations operations = operationsProvider.getIfAvailable();
        if (operations == null) {
            return;
        }
        try {
            IndexOperations indexOps = operations.indexOps(IndexCoordinates.of(properties.getEsIndex()));
            if (!indexOps.exists()) {
                indexOps.create();
                indexOps.putMapping(mapping());
            }
        } catch (Exception e) {
            log.warn("[TraceEsWriter] 初始化 trace 索引失败: {}", e.getMessage());
        }
    }

    public void persistAsync(TurnTrace trace) {
        if (!shouldPersist(trace)) {
            return;
        }
        MdcAsync.mdcRunAsync(() -> persist(trace));
    }

    private boolean shouldPersist(TurnTrace trace) {
        if (!properties.isEnabled() || trace == null) {
            return false;
        }
        double sampleRate = Math.max(0.0, Math.min(1.0, properties.getSampleRate()));
        return sampleRate >= 1.0 || ThreadLocalRandom.current().nextDouble() < sampleRate;
    }

    private void persist(TurnTrace trace) {
        try {
            ElasticsearchOperations operations = operationsProvider.getIfAvailable();
            if (operations == null) {
                return;
            }
            enforceDocumentLimit(trace);
            operations.save(trace, IndexCoordinates.of(properties.getEsIndex()));
        } catch (Exception e) {
            log.warn("[TraceEsWriter] 写入 turn trace 失败 turnId={}: {}", trace.getTurnId(), e.getMessage());
        }
    }

    /**
     * 落库前按配置限制单条 trace 文档大小。
     *
     * <p>字段片段在采集时已做单字段截断；这里兜住极端流式场景下节点数过多导致的文档膨胀。
     * 为保留执行前半段排障价值，采用删尾策略并追加一个截断说明节点。</p>
     */
    void enforceDocumentLimit(TurnTrace trace) {
        int maxChars = Math.max(1_000, properties.getDocMaxChars());
        if (estimateChars(trace) <= maxChars) {
            return;
        }

        int removed = 0;
        while (estimateChars(trace) > maxChars && !trace.getNodes().isEmpty()) {
            trace.getNodes().remove(trace.getNodes().size() - 1);
            removed++;
        }
        if (removed > 0) {
            TurnTrace.Node marker = new TurnTrace.Node();
            marker.setOrder(trace.getNextNodeOrder().getAndIncrement());
            marker.setNodeName("trace-truncated");
            marker.setType("system");
            marker.setStatus("SUCCESS");
            marker.setContentSnippet("Trace document exceeded fish.trace.doc-max-chars; removed tail nodes=" + removed);
            trace.getNodes().add(marker);
            while (estimateChars(trace) > maxChars && !trace.getNodes().isEmpty()) {
                trace.getNodes().remove(trace.getNodes().size() - 1);
            }
        }
    }

    private int estimateChars(TurnTrace trace) {
        int total = 256;
        total += length(trace.getTurnId());
        total += length(trace.getSessionId());
        total += length(trace.getTraceId());
        total += length(trace.getStatus());
        total += length(trace.getRagInjected());
        total += length(trace.getMemoryInjected());
        for (TurnTrace.Node node : trace.getNodes()) {
            total += 64;
            total += length(node.getType());
            total += length(node.getNodeName());
            total += length(node.getStatus());
            total += length(node.getContentSnippet());
        }
        return total;
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private Document mapping() {
        Document mapping = Document.create();
        mapping.put("properties", Map.ofEntries(
                Map.entry("turn_id", Map.of("type", "keyword")),
                Map.entry("session_id", Map.of("type", "keyword")),
                Map.entry("trace_id", Map.of("type", "keyword")),
                Map.entry("status", Map.of("type", "keyword")),
                Map.entry("start_time_ms", Map.of("type", "date", "format", "epoch_millis")),
                Map.entry("total_latency_ms", Map.of("type", "long")),
                Map.entry("rag_injected", Map.of("type", "text")),
                Map.entry("memory_injected", Map.of("type", "text"))
        ));
        return mapping;
    }
}
