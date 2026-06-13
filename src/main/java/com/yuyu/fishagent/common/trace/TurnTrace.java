package com.yuyu.fishagent.common.trace;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单轮对话执行 trace。
 *
 * <p>只存节点片段与指标，不保存完整 prompt/answer，兼顾排障价值和存储边界。</p>
 */
@Data
@Document(indexName = "fish-trace")
public class TurnTrace {

    @Id
    private String turnId;

    @Field(name = "session_id", type = FieldType.Keyword)
    private String sessionId;

    @Field(name = "trace_id", type = FieldType.Keyword)
    private String traceId;

    @Field(name = "start_time_ms")
    private long startTimeMs;

    @Field(name = "total_latency_ms")
    private long totalLatencyMs;

    @Field(name = "status", type = FieldType.Keyword)
    private String status = "RUNNING";

    @Field(name = "rag_injected", type = FieldType.Text)
    private String ragInjected;

    @Field(name = "memory_injected", type = FieldType.Text)
    private String memoryInjected;

    private TokenUsage tokenUsage = new TokenUsage();

    /**
     * NodeOutput 可能由 Reactor 不同线程回调，列表必须允许并发追加。
     *
     * <p>trace 节点通常只在单轮对话内追加、结束后整体读取；CopyOnWriteArrayList
     * 牺牲少量写入成本，换取迭代/序列化时的稳定快照，避免 ArrayList 并发扩容损坏。</p>
     */
    private List<Node> nodes = new CopyOnWriteArrayList<>();

    @Transient
    private AtomicBoolean completed = new AtomicBoolean(false);

    @Transient
    private AtomicInteger nextNodeOrder = new AtomicInteger(1);

    @Data
    public static class Node {
        private int order;
        private String type;
        private String nodeName;
        private String contentSnippet;
        private long latencyMs;
        private String status;
        /** 工具结果治理处置方式：truncated / summarized / retrieved；普通节点为空。 */
        private String disposition;
    }

    @Data
    public static class TokenUsage {
        private long prompt;
        private long completion;
    }
}
