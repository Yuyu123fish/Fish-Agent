package com.yuyu.fishagent.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.fishagent.auth.context.UserContext;
import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.rag.dto.DocumentMetadataPageResponse;
import com.yuyu.fishagent.rag.dto.DocumentTaskStatusResponse;
import com.yuyu.fishagent.rag.dto.KnowledgeUploadResponse;
import com.yuyu.fishagent.rag.dto.MultipartAbortRequest;
import com.yuyu.fishagent.rag.dto.MultipartCompleteRequest;
import com.yuyu.fishagent.rag.dto.MultipartInitRequest;
import com.yuyu.fishagent.rag.dto.MultipartInitResponse;
import com.yuyu.fishagent.rag.dto.MultipartPartResponse;
import com.yuyu.fishagent.rag.entity.DocumentMetadata;
import com.yuyu.fishagent.auth.enums.UserRole;
import com.yuyu.fishagent.rag.mapper.DocumentMetadataMapper;
import com.yuyu.fishagent.rag.service.KnowledgeIngestionService;
import com.yuyu.fishagent.rag.service.KnowledgeManageService;
import com.yuyu.fishagent.rag.service.MultipartInitResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.Objects;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 知识库上传与任务状态查询。
 */
@RestController
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeIngestionService knowledgeIngestionService;
    private final KnowledgeManageService knowledgeManageService;
    private final DocumentMetadataMapper documentMetadataMapper;

    @PostMapping(value = "/api/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeUploadResponse uploadUser(@RequestPart("file") MultipartFile file) throws Exception {
        Long uid = UserContextHolder.currentUserIdOrNull();
        if (uid == null) {
            throw new IllegalStateException("未登录");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        try (InputStream in = file.getInputStream()) {
            String taskId = knowledgeIngestionService.ingestUserFile(uid, file.getOriginalFilename(), in, file.getSize(), file.getContentType());
            return new KnowledgeUploadResponse(taskId);
        }
    }

    @PostMapping(value = "/api/admin/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeUploadResponse uploadAdmin(@RequestPart("file") MultipartFile file) throws Exception {
        requireAdmin();
        Long uid = UserContextHolder.currentUserIdOrNull();
        if (uid == null) {
            throw new IllegalStateException("未登录");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        try (InputStream in = file.getInputStream()) {
            String taskId = knowledgeIngestionService.ingestAdminFile(uid, file.getOriginalFilename(), in, file.getSize(), file.getContentType());
            return new KnowledgeUploadResponse(taskId);
        }
    }

    @PostMapping(value = "/api/knowledge/upload/init", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MultipartInitResponse initMultipartUser(@RequestBody MultipartInitRequest req) throws Exception {
        Long uid = UserContextHolder.currentUserIdOrNull();
        if (uid == null) {
            throw new IllegalStateException("未登录");
        }
        MultipartInitResult r = knowledgeIngestionService.initMultipartUpload(uid, req.getFileName(), req.getFileSize(),
                req.getContentType(), DocumentMetadata.SCOPE_PRIVATE, "user/" + uid + "/");
        return new MultipartInitResponse(r.taskId(), r.uploadId(), r.minioPath());
    }

    @PostMapping(value = "/api/admin/knowledge/upload/init", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MultipartInitResponse initMultipartAdmin(@RequestBody MultipartInitRequest req) throws Exception {
        requireAdmin();
        Long uid = UserContextHolder.currentUserIdOrNull();
        if (uid == null) {
            throw new IllegalStateException("未登录");
        }
        MultipartInitResult r = knowledgeIngestionService.initMultipartUpload(uid, req.getFileName(), req.getFileSize(),
                req.getContentType(), DocumentMetadata.SCOPE_PUBLIC, "admin/");
        return new MultipartInitResponse(r.taskId(), r.uploadId(), r.minioPath());
    }

    @PostMapping(value = "/api/knowledge/upload/chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MultipartPartResponse uploadChunk(
            @RequestParam String taskId,
            @RequestParam String uploadId,
            @RequestParam String minioPath,
            @RequestParam int partNumber,
            @RequestPart("chunk") MultipartFile chunk) throws Exception {
        DocumentMetadata row = loadPendingTaskForMutation(taskId.trim());
        try (InputStream in = chunk.getInputStream()) {
            String etag = knowledgeIngestionService.uploadMultipartPart(row, uploadId, minioPath.trim(), partNumber, in, chunk.getSize());
            return new MultipartPartResponse(etag);
        }
    }

    @PostMapping(value = "/api/knowledge/upload/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgeUploadResponse completeMultipart(@RequestBody MultipartCompleteRequest req) throws Exception {
        DocumentMetadata row = loadPendingTaskForMutation(req.getTaskId().trim());
        knowledgeIngestionService.completeMultipartUpload(row, req.getUploadId(), req.getMinioPath().trim(), req.getParts());
        return new KnowledgeUploadResponse(row.getTaskId());
    }

    @PostMapping(value = "/api/knowledge/upload/abort", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void abortMultipart(@RequestBody MultipartAbortRequest req) throws Exception {
        DocumentMetadata row = loadPendingTaskForMutation(req.getTaskId().trim());
        knowledgeIngestionService.abortMultipartUpload(row, req.getUploadId(), req.getMinioPath().trim());
    }

    /**
     * 当前用户上传任务分页列表（知识库管理页）。
     */
    @GetMapping("/api/knowledge/documents")
    public DocumentMetadataPageResponse listMyDocuments(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        Long uid = UserContextHolder.currentUserIdOrNull();
        if (uid == null) {
            throw new IllegalStateException("未登录");
        }
        return knowledgeManageService.listForCurrentUser(uid, page, size);
    }

    /**
     * 管理员：全部上传任务。
     */
    @GetMapping("/api/admin/knowledge/documents")
    public DocumentMetadataPageResponse listAllDocuments(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        requireAdmin();
        return knowledgeManageService.listAll(page, size);
    }

    /**
     * 删除文档任务（本人或管理员）：先清 ES 切片，再删对象存储，最后删 MySQL 记录。
     */
    @DeleteMapping("/api/knowledge/documents/{taskId}")
    public void deleteDocument(@PathVariable String taskId) {
        UserContext ctx = UserContextHolder.get();
        Long uid = ctx == null ? null : ctx.userId();
        knowledgeManageService.deleteByTaskId(taskId, uid, isAdmin());
    }

    @GetMapping("/api/knowledge/tasks/{taskId}")
    public DocumentTaskStatusResponse taskStatus(@PathVariable String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        DocumentMetadata row = documentMetadataMapper.selectOne(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, taskId.trim()));
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "任务不存在");
        }
        UserContext ctx = UserContextHolder.get();
        Long uid = ctx == null ? null : ctx.userId();
        if (!isAdmin() && (uid == null || !uid.equals(row.getUserId()))) {
            throw new ResponseStatusException(FORBIDDEN, "无权查看该任务");
        }
        return new DocumentTaskStatusResponse(row.getStatus(), row.getErrorMsg());
    }

    /**
     * 加载 PENDING 任务并校验当前用户可变更（本人或 ADMIN）。
     */
    private DocumentMetadata loadPendingTaskForMutation(String taskId) {
        DocumentMetadata row = documentMetadataMapper.selectOne(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, taskId));
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "任务不存在");
        }
        if (!DocumentMetadata.STATUS_PENDING.equals(row.getStatus())) {
            throw new IllegalArgumentException("任务状态不允许该操作");
        }
        UserContext ctx = UserContextHolder.get();
        Long uid = ctx == null ? null : ctx.userId();
        if (!isAdmin() && (uid == null || !Objects.equals(uid, row.getUserId()))) {
            throw new ResponseStatusException(FORBIDDEN, "无权操作该任务");
        }
        return row;
    }

    private static boolean isAdmin() {
        UserContext ctx = UserContextHolder.get();
        if (ctx == null) {
            return false;
        }
        String role = ctx.role();
        return role != null && UserRole.ADMIN.name().equalsIgnoreCase(role.trim());
    }

    private static void requireAdmin() {
        if (!isAdmin()) {
            throw new ResponseStatusException(FORBIDDEN, "需要管理员权限");
        }
    }
}
