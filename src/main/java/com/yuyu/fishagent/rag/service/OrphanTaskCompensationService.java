package com.yuyu.fishagent.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.fishagent.rag.document.PublicKnowledgeDocument;
import com.yuyu.fishagent.rag.document.UserMemoryDocument;
import com.yuyu.fishagent.rag.config.KnowledgeProperties;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.rag.entity.DocumentMetadata;
import com.yuyu.fishagent.rag.mapper.DocumentMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.DeleteQuery;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 孤儿 PROCESSING 任务补偿：Worker 崩溃或长时间无 ACK 时，清理 ES 中可能残留的切片并将 MySQL 标记为 FAILED。
 * <p>与 {@link KnowledgeManageService#deleteEsChunks} 使用相同的 delete_by_query 语义，仅不删 RustFS / 不删元数据行（改为 UPDATE）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fish.knowledge.compensation.enabled", havingValue = "true", matchIfMissing = true)
public class OrphanTaskCompensationService {

    private static final String WORKER_TIMEOUT_MSG = "worker timeout";

    private final DocumentMetadataMapper documentMetadataMapper;
    private final ObjectProvider<ElasticsearchOperations> operationsProvider;
    private final KnowledgeProperties knowledgeProperties;
    private final MemoryProperties memoryProperties;

    /**
     * 每分钟执行一次；单轮调度异常不影响后续触发。
     */
    @Scheduled(fixedDelay = 60_000L)
    public void compensateOrphanTasks() {
        try {
            runCompensation();
        } catch (Exception e) {
            log.warn("[OrphanCompensation] 本轮调度失败（已吞，不影响主业务）: {}", e.getMessage());
        }
    }

    private void runCompensation() {
        int timeoutMinutes = knowledgeProperties.getCompensation().getTimeoutMinutes();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, timeoutMinutes));

        List<DocumentMetadata> orphans = documentMetadataMapper.selectList(
                Wrappers.<DocumentMetadata>lambdaQuery()
                        .eq(DocumentMetadata::getStatus, DocumentMetadata.STATUS_PROCESSING)
                        .lt(DocumentMetadata::getUpdatedAt, cutoff));

        if (orphans.isEmpty()) {
            return;
        }

        log.info("[OrphanCompensation] 发现孤儿任务 count={}, timeoutMinutes={}, cutoff={}",
                orphans.size(), timeoutMinutes, cutoff);

        for (DocumentMetadata row : orphans) {
            try {
                deleteEsChunksQuietly(row);
                markFailedWorkerTimeout(row.getTaskId());
                log.info("[OrphanCompensation] 已补偿 taskId={} scope={} userId={}",
                        row.getTaskId(), row.getScopeType(), row.getUserId());
            } catch (Exception e) {
                log.warn("[OrphanCompensation] 单条补偿失败 taskId={}: {}", row.getTaskId(), e.getMessage());
            }
        }
    }

    /**
     * 按 scope 清理 ES：与 {@link KnowledgeManageService} 删除路径一致（含 fish-user-memory 遗留 document 切片）。
     */
    private void deleteEsChunksQuietly(DocumentMetadata row) {
        ElasticsearchOperations ops = operationsProvider.getIfAvailable();
        if (ops == null) {
            log.warn("[OrphanCompensation] ElasticsearchOperations 不可用，跳过 ES 清理 taskId={}", row.getTaskId());
            return;
        }
        String taskId = row.getTaskId();
        try {
            if (DocumentMetadata.SCOPE_PRIVATE.equals(row.getScopeType())) {
                IndexCoordinates userKb = IndexCoordinates.of(knowledgeProperties.getUserKnowledgeIndexName());
                NativeQuery nqUserKb = NativeQuery.builder()
                        .withQuery(q -> q.bool(b -> b
                                .must(m -> m.term(t -> t.field("doc_id").value(taskId)))
                                .must(m -> m.term(t -> t.field("user_id").value(String.valueOf(row.getUserId()))))))
                        .build();
                ops.delete(DeleteQuery.builder(nqUserKb).build(), UserMemoryDocument.class, userKb);

                IndexCoordinates memoryIdx = IndexCoordinates.of(memoryProperties.getLongTermIndexName());
                NativeQuery nqLegacy = NativeQuery.builder()
                        .withQuery(q -> q.bool(b -> b
                                .must(m -> m.term(t -> t.field("doc_id").value(taskId)))
                                .must(m -> m.term(t -> t.field("user_id").value(String.valueOf(row.getUserId()))))
                                .must(m -> m.term(t -> t.field("source_type").value("document")))))
                        .build();
                ops.delete(DeleteQuery.builder(nqLegacy).build(), UserMemoryDocument.class, memoryIdx);
            } else if (DocumentMetadata.SCOPE_PUBLIC.equals(row.getScopeType())) {
                IndexCoordinates index = IndexCoordinates.of(knowledgeProperties.getPublicIndexName());
                NativeQuery nq = NativeQuery.builder()
                        .withQuery(q -> q.term(t -> t.field("doc_id").value(taskId)))
                        .build();
                ops.delete(DeleteQuery.builder(nq).build(), PublicKnowledgeDocument.class, index);
            } else {
                log.warn("[OrphanCompensation] 未知 scope_type={}，跳过 ES taskId={}", row.getScopeType(), taskId);
            }
        } catch (Exception e) {
            log.warn("[OrphanCompensation] ES delete_by_query 失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    private void markFailedWorkerTimeout(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        documentMetadataMapper.update(null, Wrappers.<DocumentMetadata>lambdaUpdate()
                .eq(DocumentMetadata::getTaskId, taskId.trim())
                .set(DocumentMetadata::getStatus, DocumentMetadata.STATUS_FAILED)
                .set(DocumentMetadata::getErrorMsg, WORKER_TIMEOUT_MSG)
                .set(DocumentMetadata::getUpdatedAt, LocalDateTime.now()));
    }
}
