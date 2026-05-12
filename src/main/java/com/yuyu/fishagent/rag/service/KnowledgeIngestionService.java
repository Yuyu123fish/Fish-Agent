package com.yuyu.fishagent.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.fishagent.rag.config.KnowledgeProperties;
import com.yuyu.fishagent.rag.dto.MultipartPartInfo;
import com.yuyu.fishagent.rag.entity.DocumentMetadata;
import com.yuyu.fishagent.rag.mapper.DocumentMetadataMapper;
import com.yuyu.fishagent.rag.service.RustFsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库上传：写入 RustFS、落库 {@code document_metadata}、投递 Redis Stream；解析与向量化由 Python Worker 完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    private final ObjectProvider<RustFsService> rustFsProvider;
    private final DocumentMetadataMapper documentMetadataMapper;
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    private final KnowledgeProperties knowledgeProperties;

    /**
     * 用户上传私有文档：scope PRIVATE，对象键前缀 {@code user/{userId}/}。
     *
     * @param stream      文件流（调用方负责关闭）
     * @param size        字节长度（须与流一致）
     * @return {@code task_id}（UUID），供前端轮询与 Stream 追踪
     */
    public String ingestUserFile(Long userId, String originalFilename, InputStream stream, long size, String contentType) throws Exception {
        return ingest(userId, originalFilename, stream, size, contentType, DocumentMetadata.SCOPE_PRIVATE, "user/" + userId + "/");
    }

    /**
     * 管理员上传公共文档：scope PUBLIC，对象键前缀 {@code admin/}。
     */
    public String ingestAdminFile(Long userId, String originalFilename, InputStream stream, long size, String contentType) throws Exception {
        return ingest(userId, originalFilename, stream, size, contentType, DocumentMetadata.SCOPE_PUBLIC, "admin/");
    }

    private String ingest(Long userId, String originalFilename, InputStream stream, long size, String contentType,
                          String scopeType, String objectKeyPrefix) throws Exception {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (stream == null || size <= 0) {
            throw new IllegalArgumentException("文件内容为空或大小无效");
        }
        RustFsService rustFs = rustFsProvider.getIfAvailable();
        if (rustFs == null) {
            throw new IllegalArgumentException("知识库上传需要开启 fish.rustfs.enabled=true 并正确配置对象存储");
        }

        String taskId = UUID.randomUUID().toString().replace("-", "");
        String safeName = sanitizeFileName(originalFilename);
        String minioPath = objectKeyPrefix + taskId + "_" + safeName;

        rustFs.putDocObject(minioPath, stream, size, contentType);

        LocalDateTime now = LocalDateTime.now();
        DocumentMetadata row = new DocumentMetadata();
        row.setTaskId(taskId);
        row.setUserId(userId);
        row.setFileName(originalFilename == null || originalFilename.isBlank() ? safeName : originalFilename.trim());
        row.setFileSize(size);
        row.setMinioPath(minioPath);
        row.setScopeType(scopeType);
        row.setStatus(DocumentMetadata.STATUS_PENDING);
        row.setErrorMsg(null);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);

        try {
            documentMetadataMapper.insert(row);
        } catch (Exception e) {
            log.warn("[KnowledgeIngestion] MySQL 插入失败，回滚删除对象 path={}: {}", minioPath, e.getMessage());
            try {
                rustFs.deleteDocObject(minioPath);
            } catch (Exception ex) {
                log.warn("[KnowledgeIngestion] 回滚删除对象失败 path={}: {}", minioPath, ex.getMessage());
            }
            throw e;
        }

        try {
            publishStream(taskId, minioPath, scopeType, userId, row.getFileName(), size);
        } catch (Exception e) {
            log.error("[KnowledgeIngestion] Redis Stream 投递失败 taskId={}: {}", taskId, e.getMessage());
            documentMetadataMapper.update(null, Wrappers.<DocumentMetadata>lambdaUpdate()
                    .eq(DocumentMetadata::getId, row.getId())
                    .set(DocumentMetadata::getStatus, DocumentMetadata.STATUS_FAILED)
                    .set(DocumentMetadata::getErrorMsg, truncate("Redis Stream 投递失败: " + e.getMessage(), 2000))
                    .set(DocumentMetadata::getUpdatedAt, LocalDateTime.now()));
            try {
                rustFs.deleteDocObject(minioPath);
            } catch (Exception ex) {
                log.warn("[KnowledgeIngestion] Stream 失败后删除对象失败 path={}: {}", minioPath, ex.getMessage());
            }
            throw new IllegalArgumentException("文档已记录但队列投递失败，请稍后重试或联系管理员", e);
        }

        log.info("[KnowledgeIngestion] 已提交任务 taskId={}, path={}, scope={}", taskId, minioPath, scopeType);
        return taskId;
    }

    /**
     * 分片上传初始化：仅落库 PENDING；分片暂存 {@code staging/{taskId}/{partNumber}}，完成后 compose 到 {@code minioPath}。
     * <p>约定 {@code uploadId} 与 {@code taskId} 相同（MinIO Java SDK 8.5.x 未暴露 CreateMultipartUpload API，改用 staging + compose）。</p>
     */
    public MultipartInitResult initMultipartUpload(Long userId, String originalFilename, long fileSize, String contentType,
                                                   String scopeType, String objectKeyPrefix) throws Exception {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (fileSize <= 0) {
            throw new IllegalArgumentException("fileSize 无效");
        }
        RustFsService rustFs = rustFsProvider.getIfAvailable();
        if (rustFs == null) {
            throw new IllegalArgumentException("知识库上传需要开启 fish.rustfs.enabled=true 并正确配置对象存储");
        }
        // contentType 在 init 阶段记录用途有限；最终对象 MIME 由 compose 结果决定，必要时可后续扩展元数据表字段。
        if (contentType != null) {
            log.debug("[KnowledgeIngestion] multipart init contentType={}", contentType);
        }

        String taskId = UUID.randomUUID().toString().replace("-", "");
        String safeName = sanitizeFileName(originalFilename);
        String minioPath = objectKeyPrefix + taskId + "_" + safeName;

        LocalDateTime now = LocalDateTime.now();
        DocumentMetadata row = new DocumentMetadata();
        row.setTaskId(taskId);
        row.setUserId(userId);
        row.setFileName(originalFilename == null || originalFilename.isBlank() ? safeName : originalFilename.trim());
        row.setFileSize(fileSize);
        row.setMinioPath(minioPath);
        row.setScopeType(scopeType);
        row.setStatus(DocumentMetadata.STATUS_PENDING);
        row.setErrorMsg(null);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);

        documentMetadataMapper.insert(row);

        log.info("[KnowledgeIngestion] multipart 已初始化 taskId={}, path={}", taskId, minioPath);
        return new MultipartInitResult(taskId, taskId, minioPath);
    }

    /**
     * 上传单个分片至 staging。
     */
    public String uploadMultipartPart(DocumentMetadata row, String uploadId, String minioPath, int partNumber,
                                      InputStream data, long size) throws Exception {
        assertRowMatches(row, minioPath);
        requireUploadIdMatchesTask(uploadId, row.getTaskId());
        if (partNumber < 1) {
            throw new IllegalArgumentException("partNumber 必须从 1 开始");
        }
        if (data == null || size <= 0) {
            throw new IllegalArgumentException("分片内容无效");
        }
        RustFsService rustFs = rustFsProvider.getIfAvailable();
        if (rustFs == null) {
            throw new IllegalArgumentException("RustFS 不可用");
        }
        String stagingKey = stagingPartKey(row.getTaskId(), partNumber);
        return rustFs.putDocObject(stagingKey, data, size, "application/octet-stream");
    }

    /**
     * compose 合并 staging 分片为最终对象并投递 Redis Stream。
     */
    public void completeMultipartUpload(DocumentMetadata row, String uploadId, String minioPath,
                                        List<MultipartPartInfo> parts) throws Exception {
        assertRowMatches(row, minioPath);
        requireUploadIdMatchesTask(uploadId, row.getTaskId());
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("parts 不能为空");
        }
        RustFsService rustFs = rustFsProvider.getIfAvailable();
        if (rustFs == null) {
            throw new IllegalArgumentException("RustFS 不可用");
        }

        List<MultipartPartInfo> sorted = new ArrayList<>(parts);
        sorted.sort(Comparator.comparingInt(MultipartPartInfo::getPartNumber));
        List<String> sourceKeys = new ArrayList<>(sorted.size());
        for (MultipartPartInfo p : sorted) {
            sourceKeys.add(stagingPartKey(row.getTaskId(), p.getPartNumber()));
        }

        try {
            rustFs.composeDocObject(sourceKeys, minioPath);
        } catch (Exception e) {
            log.error("[KnowledgeIngestion] compose 合并失败 taskId={}: {}", row.getTaskId(), e.getMessage());
            documentMetadataMapper.update(null, Wrappers.<DocumentMetadata>lambdaUpdate()
                    .eq(DocumentMetadata::getId, row.getId())
                    .set(DocumentMetadata::getStatus, DocumentMetadata.STATUS_FAILED)
                    .set(DocumentMetadata::getErrorMsg, truncate("合并分片失败: " + e.getMessage(), 2000))
                    .set(DocumentMetadata::getUpdatedAt, LocalDateTime.now()));
            try {
                rustFs.deleteObjectsByPrefix(stagingPrefix(row.getTaskId()));
            } catch (Exception ex) {
                log.warn("[KnowledgeIngestion] compose 失败后清理 staging: {}", ex.getMessage());
            }
            throw e;
        }

        try {
            rustFs.deleteObjectsByPrefix(stagingPrefix(row.getTaskId()));
        } catch (Exception e) {
            log.warn("[KnowledgeIngestion] compose 成功后清理 staging 失败 taskId={}: {}", row.getTaskId(), e.getMessage());
        }

        try {
            publishStream(row.getTaskId(), minioPath, row.getScopeType(), row.getUserId(), row.getFileName(), row.getFileSize());
        } catch (Exception e) {
            log.error("[KnowledgeIngestion] multipart 完成后 Stream 投递失败 taskId={}: {}", row.getTaskId(), e.getMessage());
            documentMetadataMapper.update(null, Wrappers.<DocumentMetadata>lambdaUpdate()
                    .eq(DocumentMetadata::getId, row.getId())
                    .set(DocumentMetadata::getStatus, DocumentMetadata.STATUS_FAILED)
                    .set(DocumentMetadata::getErrorMsg, truncate("Redis Stream 投递失败: " + e.getMessage(), 2000))
                    .set(DocumentMetadata::getUpdatedAt, LocalDateTime.now()));
            try {
                rustFs.deleteDocObject(minioPath);
            } catch (Exception ex) {
                log.warn("[KnowledgeIngestion] Stream 失败后删除对象失败: {}", ex.getMessage());
            }
            throw new IllegalArgumentException("文档已合并但队列投递失败，请稍后重试或联系管理员", e);
        }

        log.info("[KnowledgeIngestion] multipart 已完成并入队 taskId={}, path={}", row.getTaskId(), minioPath);
    }

    /**
     * 取消分片上传：删除 staging 对象并将任务标为失败。
     */
    public void abortMultipartUpload(DocumentMetadata row, String uploadId, String minioPath) throws Exception {
        assertRowMatches(row, minioPath);
        requireUploadIdMatchesTask(uploadId, row.getTaskId());
        RustFsService rustFs = rustFsProvider.getIfAvailable();
        if (rustFs != null) {
            try {
                rustFs.deleteObjectsByPrefix(stagingPrefix(row.getTaskId()));
            } catch (Exception e) {
                log.warn("[KnowledgeIngestion] abort 清理 staging taskId={}: {}", row.getTaskId(), e.getMessage());
            }
        }
        documentMetadataMapper.update(null, Wrappers.<DocumentMetadata>lambdaUpdate()
                .eq(DocumentMetadata::getId, row.getId())
                .set(DocumentMetadata::getStatus, DocumentMetadata.STATUS_FAILED)
                .set(DocumentMetadata::getErrorMsg, truncate("上传已取消", 2000))
                .set(DocumentMetadata::getUpdatedAt, LocalDateTime.now()));
    }

    private static String stagingPrefix(String taskId) {
        return "staging/" + taskId + "/";
    }

    private static String stagingPartKey(String taskId, int partNumber) {
        return stagingPrefix(taskId) + partNumber;
    }

    private static void requireUploadIdMatchesTask(String uploadId, String taskId) {
        if (uploadId == null || taskId == null || !uploadId.trim().equals(taskId)) {
            throw new IllegalArgumentException("uploadId 与任务不匹配");
        }
    }

    private static void assertRowMatches(DocumentMetadata row, String minioPath) {
        if (row == null || minioPath == null || minioPath.isBlank()) {
            throw new IllegalArgumentException("任务或路径无效");
        }
        if (!DocumentMetadata.STATUS_PENDING.equals(row.getStatus())) {
            throw new IllegalArgumentException("任务状态不允许该操作");
        }
        if (!minioPath.trim().equals(row.getMinioPath())) {
            throw new IllegalArgumentException("minio_path 与任务不匹配");
        }
    }

    private void publishStream(String taskId, String minioPath, String scopeType, Long userId, String fileName, long fileSize) {
        StringRedisTemplate redis = stringRedisTemplateProvider.getIfAvailable();
        if (redis == null) {
            throw new IllegalArgumentException("StringRedisTemplate 不可用（请检查 Redis 配置）");
        }
        String streamKey = knowledgeProperties.getDocumentIngestStreamKey();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        body.put("minio_path", minioPath);
        body.put("scope_type", scopeType);
        body.put("user_id", String.valueOf(userId));
        body.put("file_name", fileName == null ? "" : fileName);
        body.put("file_size", String.valueOf(fileSize));

        MapRecord<String, String, String> record = StreamRecords.mapBacked(body).withStreamKey(streamKey);
        RecordId recordId = redis.opsForStream().add(record);
        log.debug("[KnowledgeIngestion] XADD {} -> {}", streamKey, recordId);
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "upload.bin";
        }
        String s = name.replace('\\', '_').replace('/', '_').trim();
        if (s.length() > 200) {
            s = s.substring(0, 200);
        }
        return s.isBlank() ? "upload.bin" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
