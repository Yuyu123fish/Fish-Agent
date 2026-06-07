package com.yuyu.fishagent.card.dto;

/**
 * 确认发现关系请求；relationType 默认 related_to，也允许用户后续改为其他类型。
 */
public record ConfirmRelationRequest(
        Long fromCardId,
        Long toCardId,
        String relationType
) {
}
