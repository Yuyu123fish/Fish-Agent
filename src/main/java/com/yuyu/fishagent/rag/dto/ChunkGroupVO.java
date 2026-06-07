package com.yuyu.fishagent.rag.dto;

import java.util.List;

/**
 * 文档切片分组与 AI 摘要。
 */
public record ChunkGroupVO(
        String taskId,
        String fileName,
        String summary,
        Integer totalChunks,
        List<ChunkGroupItemVO> groups
) {
}
