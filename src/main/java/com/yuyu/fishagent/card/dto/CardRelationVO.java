package com.yuyu.fishagent.card.dto;

/**
 * 卡片详情里的关联展示对象，direction 表示当前卡片相对该关系的方向。
 */
public record CardRelationVO(
        Long id,
        Long cardId,
        String cardTitle,
        String relationType,
        Float confidence,
        String direction
) {
}
