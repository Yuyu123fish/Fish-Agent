package com.yuyu.fishagent.card.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewStatsResponse {
    private int totalCards;
    private int mastered;
    private int learning;
    private int dueToday;
    private int streakDays;
    private Map<String, Integer> reviewCalendar;
    private List<Integer> weeklyActivity;
}
