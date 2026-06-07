package com.yuyu.fishagent.card.extract;

import java.util.List;

/**
 * 模型输出的卡片草稿；进入数据库前还会由 Service 做用户、来源和状态补齐。
 */
public record ExtractedCardDraft(
        String title,
        String content,
        List<String> keywords,
        String cardType,
        String groupName
) {
}
