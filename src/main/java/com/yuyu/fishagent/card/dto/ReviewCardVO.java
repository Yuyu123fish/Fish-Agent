package com.yuyu.fishagent.card.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewCardVO {
    private Long id;
    private String title;
    private String content;
    private List<String> keywords;
    private String cardType;
    private String groupPath;
    private ReviewInfoDTO reviewInfo;
}
