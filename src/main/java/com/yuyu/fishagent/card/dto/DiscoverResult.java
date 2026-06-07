package com.yuyu.fishagent.card.dto;

import java.util.List;

/**
 * 智能关联发现结果。
 */
public record DiscoverResult(
        List<RelationSuggestion> suggestions,
        int total
) {
}
