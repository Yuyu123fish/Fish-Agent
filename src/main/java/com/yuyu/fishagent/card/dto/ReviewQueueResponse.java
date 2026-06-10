package com.yuyu.fishagent.card.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewQueueResponse {
    private List<ReviewCardVO> cards;
    private int totalDue;
    private int totalNew;
}
