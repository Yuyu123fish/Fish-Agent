package com.yuyu.fishagent.rag.dto;

/**
 * 与某张知识卡片相关的源文档切片。
 */
public record RelatedChunkVO(
        String taskId,
        String fileName,
        Integer chunkIndex,
        String contentPreview,
        Double similarity
) {
}
