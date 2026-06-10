package com.yuyu.fishagent.card.dto;

import java.time.LocalDateTime;
import java.util.List;
import com.yuyu.fishagent.rag.dto.RelatedChunkVO;

/**
 * 卡片详情响应，包含正文和双向关联列表。
 */
public record CardVO(
        Long id,
        String title,
        String content,
        List<String> keywords,
        String cardType,
        String sourceType,
        String sourceId,
        String status,
        String groupName,
        Long groupId,
        String groupPath,
        List<CardRelationVO> relations,
        List<RelatedChunkVO> relatedChunks,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        ReviewInfoDTO reviewInfo
) {
}
