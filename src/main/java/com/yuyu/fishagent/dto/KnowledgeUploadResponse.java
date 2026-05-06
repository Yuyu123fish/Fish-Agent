package com.yuyu.fishagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库上传成功后的响应：前端用 {@code taskId} 轮询解析进度。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeUploadResponse {

    private String taskId;
}
