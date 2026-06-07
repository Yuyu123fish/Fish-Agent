package com.yuyu.fishagent.card.dto;

import java.util.List;

/**
 * AI 提取结果：卡片先以 pending 入库，前端预览后再批量确认或稍后处理。
 */
public record ExtractResult(
        int extractedCount,
        List<Long> cardIds,
        List<CardVO> cards,
        List<ExtractRelationVO> relations
) {
}
