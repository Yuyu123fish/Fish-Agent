package com.yuyu.fishagent.card.dto;

import java.util.List;

/**
 * 编辑知识卡片请求体；只允许修改用户可控字段，来源与状态由后端维护。
 */
public record CardUpdateRequest(
        String title,
        String content,
        List<String> keywords,
        String cardType,
        String groupName,
        Long groupId
) {
}
