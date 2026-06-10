package com.yuyu.fishagent.card.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewInfoDTO {
    private LocalDateTime nextReviewAt;
    private int reviewCount;
    private LocalDateTime lastReviewedAt;
    private double easinessFactor;
    private int intervalDays;
    private int repetition;
}
