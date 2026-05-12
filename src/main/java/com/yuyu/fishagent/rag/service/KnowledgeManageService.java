package com.yuyu.fishagent.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuyu.fishagent.rag.document.PublicKnowledgeDocument;
import com.yuyu.fishagent.rag.document.UserMemoryDocument;
import com.yuyu.fishagent.rag.config.KnowledgeProperties;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.rag.dto.DocumentMetadataPageResponse;
import com.yuyu.fishagent.rag.dto.DocumentMetadataResponse;
import com.yuyu.fishagent.rag.entity.DocumentMetadata;
import com.yuyu.fishagent.rag.mapper.DocumentMetadataMapper;
import com.yuyu.fishagent.rag.service.RustFsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.DeleteQuery;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.format.DateTimeFormatter;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 知识库任务查询与删除：与 {@link KnowledgeIngestionService}（写入）解耦。
 * <p>删除顺序（尽力清理）：① ES 切片（delete_by_query）② RustFS 原文件 ③ MySQL 元数据，避免先删库导致孤儿切片。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeManageService {

    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final DocumentMetadataMapper documentMetadataMapper;
    private final ObjectProvider<ElasticsearchOperations> operationsProvider;
    private final KnowledgeProperties knowledgeProperties;
    private final MemoryProperties memoryProperties;
    private final ObjectProvider<RustFsService> rustFsProvider;

    /**
     * 当前用户可见的上传任务（按更新时间倒序）。
     */
    public DocumentMetadataPageResponse listForCurrentUser(Long userId, long page, long size) {
        if (userId == null) {
            throw new IllegalStateException("未登录");
        }
        Page<DocumentMetadata> p = new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size)));
        documentMetadataMapper.selectPage(p, Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getUserId, userId)
                .orderByDesc(DocumentMetadata::getUpdatedAt));
        return toPageResponse(p);
    }

    /**
     * 管理员：全部上传任务。
     */
    public DocumentMetadataPageResponse listAll(long page, long size) {
        Page<DocumentMetadata> p = new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size)));
        documentMetadataMapper.selectPage(p, Wrappers.<DocumentMetadata>lambdaQuery()
                .orderByDesc(DocumentMetadata::getUpdatedAt));
        return toPageResponse(p);
    }

    /**
     * 按 taskId 删除：本人或管理员；scope 决定 ES 目标索引（PRIVATE→user-knowledge，PUBLIC→public-knowledge）。
     */
    public void deleteByTaskId(String taskId, Long actorUserId, boolean actorIsAdmin) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        DocumentMetadata row = documentMetadataMapper.selectOne(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, taskId.trim()));
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "任务不存在");
        }
        if (!actorIsAdmin && (actorUserId == null || !actorUserId.equals(row.getUserId()))) {
            throw new ResponseStatusException(FORBIDDEN, "无权删除该文档");
        }

        deleteEsChunks(row);
        deleteRustFsQuietly(row);
        documentMetadataMapper.delete(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, row.getTaskId()));
        log.info("[KnowledgeManage] 已删除文档任务 taskId={}, scope={}", row.getTaskId(), row.getScopeType());
    }

    /**
     * scope_type → ES 索引：PRIVATE 为个人文档知识库；PUBLIC 为组织公共知识库（与 Python Worker 写入路径一致）。
     */
    private void deleteEsChunks(DocumentMetadata row) {
        ElasticsearchOperations ops = operationsProvider.getIfAvailable();
        if (ops == null) {
            log.warn("[KnowledgeManage] ElasticsearchOperations 不可用，跳过 ES 切片删除 taskId={}", row.getTaskId());
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
                // 兼容迁移前写入 fish-user-memory 的文档切片（source_type=document）
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
                DeleteQuery dq = DeleteQuery.builder(nq).build();
                ops.delete(dq, PublicKnowledgeDocument.class, index);
            } else {
                log.warn("[KnowledgeManage] 未知 scope_type={}，跳过 ES taskId={}", row.getScopeType(), taskId);
            }
        } catch (Exception e) {
            log.warn("[KnowledgeManage] ES delete_by_query 失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    private void deleteRustFsQuietly(DocumentMetadata row) {
        RustFsService rust = rustFsProvider.getIfAvailable();
        if (rust == null) {
            log.debug("[KnowledgeManage] RustFS 未启用，跳过对象删除 taskId={}", row.getTaskId());
            return;
        }
        String path = row.getMinioPath();
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            rust.deleteDocObject(path.trim());
        } catch (Exception e) {
            log.warn("[KnowledgeManage] RustFS 删除失败 path={}: {}", path, e.getMessage());
        }
    }

    private static DocumentMetadataPageResponse toPageResponse(Page<DocumentMetadata> p) {
        var records = p.getRecords().stream()
                .map(KnowledgeManageService::toResponse)
                .toList();
        return new DocumentMetadataPageResponse(records, p.getTotal(), p.getCurrent(), p.getSize());
    }

    private static DocumentMetadataResponse toResponse(DocumentMetadata m) {
        return new DocumentMetadataResponse(
                m.getTaskId(),
                m.getFileName(),
                m.getFileSize() == null ? 0L : m.getFileSize(),
                m.getScopeType(),
                m.getStatus(),
                m.getChunkCount(),
                m.getErrorMsg(),
                m.getCreatedAt() == null ? null : m.getCreatedAt().format(ISO_LOCAL),
                m.getUpdatedAt() == null ? null : m.getUpdatedAt().format(ISO_LOCAL)
        );
    }
}
