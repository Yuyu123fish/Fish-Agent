package com.yuyu.fishagent.card.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewAnswerResponse {
    private LocalDateTime nextReviewAt;
    private int intervalDays;
    private double easinessFactor;
    private int remainingDue;
}
