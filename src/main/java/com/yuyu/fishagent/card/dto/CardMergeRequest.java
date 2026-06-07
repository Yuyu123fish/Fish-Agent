package com.yuyu.fishagent.card.dto;

/**
 * 合并卡片请求：保留 keepId 的标题与正文，discardId 的关键词和关系迁移过去。
 */
public record CardMergeRequest(Long keepId, Long discardId) {
}
