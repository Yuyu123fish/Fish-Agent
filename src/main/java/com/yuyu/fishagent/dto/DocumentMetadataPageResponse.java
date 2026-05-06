package com.yuyu.fishagent.dto;

import java.util.List;

/**
 * 知识库任务分页结果。
 */
public record DocumentMetadataPageResponse(
        List<DocumentMetadataResponse> records,
        long total,
        long current,
        long size
) {
}
