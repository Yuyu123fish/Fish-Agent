package com.yuyu.fishagent.rag.service;

/**
 * 分片上传初始化结果。
 */
public record MultipartInitResult(String taskId, String uploadId, String minioPath) {
}
