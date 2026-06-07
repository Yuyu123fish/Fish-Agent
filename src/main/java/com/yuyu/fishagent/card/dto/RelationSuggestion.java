package com.yuyu.fishagent.card.dto;

import java.util.List;

/**
 * 自动发现的潜在卡片关联，包含可解释原因，便于前端给用户确认。
 */
public record RelationSuggestion(
        Long fromCardId,
        String fromTitle,
        Long toCardId,
        String toTitle,
        String suggestedType,
        float confidence,
        List<String> reasons
) {
}
