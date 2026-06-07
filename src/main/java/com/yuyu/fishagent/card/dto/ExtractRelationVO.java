package com.yuyu.fishagent.card.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 提取返回的关联摘要，供即时预览展示。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExtractRelationVO {
    private Long id;
    private Long fromCardId;
    private Long toCardId;
    private String relationType;
    private Float confidence;
}
