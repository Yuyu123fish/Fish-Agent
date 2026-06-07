package com.yuyu.fishagent.card.dto;

import java.util.List;

/**
 * 知识卡片概览统计，供顶部仪表条和分组筛选复用。
 */
public record CardStatsVO(
        long total,
        long confirmed,
        long pending,
        long relationCount,
        long weekNew,
        List<GroupTreeNode> groups
) {
}
