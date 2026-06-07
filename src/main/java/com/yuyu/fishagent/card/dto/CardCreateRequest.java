package com.yuyu.fishagent.card.dto;

import java.util.List;

/**
 * 手动创建知识卡片的请求体，用户身份由登录态注入，前端不能传 userId。
 */
public record CardCreateRequest(
        String title,
        String content,
        List<String> keywords,
        String cardType,
        String groupName,
        Long groupId
) {
}
