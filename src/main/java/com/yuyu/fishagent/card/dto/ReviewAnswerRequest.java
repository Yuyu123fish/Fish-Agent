package com.yuyu.fishagent.card.dto;

/**
 * @param cardId  required, must reference an existing confirmed card owned by the user
 * @param quality 0 = forgot, 3 = fuzzy, 5 = known; must be in [0, 5]
 */
public record ReviewAnswerRequest(Long cardId, Integer quality) {
    public ReviewAnswerRequest {
        if (cardId == null) {
            throw new IllegalArgumentException("cardId 不能为空");
        }
        if (quality == null || quality < 0 || quality > 5) {
            throw new IllegalArgumentException("quality 必须为 0~5 的整数");
        }
    }
}
