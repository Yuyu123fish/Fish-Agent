package com.yuyu.fishagent.rag.dto;

import java.util.List;

/**
 * 文档切片分页结果。
 */
public record ChunkListVO(
        String taskId,
        List<ChunkItemVO> chunks,
        Long total
) {
}
