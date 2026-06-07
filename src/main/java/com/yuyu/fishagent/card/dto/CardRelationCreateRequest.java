package com.yuyu.fishagent.card.dto;

/**
 * 手动新增关联请求；fromCardId 由路径中的当前卡片 ID 决定。
 */
public record CardRelationCreateRequest(Long toCardId, String relationType) {
}
