package com.yuyu.fishagent.card.extract;

/**
 * 模型输出的同批次卡片关系，使用 title 做临时引用，入库后再映射为 cardId。
 */
public record ExtractedRelationDraft(
        String fromTitle,
        String toTitle,
        String relationType,
        float confidence
) {
}
