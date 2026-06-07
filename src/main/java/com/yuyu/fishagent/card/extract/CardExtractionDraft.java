package com.yuyu.fishagent.card.extract;

import java.util.List;

/**
 * 一次 AI 提取的结构化草稿结果。
 */
public record CardExtractionDraft(
        List<ExtractedCardDraft> cards,
        List<ExtractedRelationDraft> relations
) {
}
