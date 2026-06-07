package com.yuyu.fishagent.rag.dto;

/**
 * 与某个切片语义相近的知识卡片。
 */
public record RelatedCardVO(
        Long cardId,
        String title,
        String cardType,
        Double similarity
) {
}
