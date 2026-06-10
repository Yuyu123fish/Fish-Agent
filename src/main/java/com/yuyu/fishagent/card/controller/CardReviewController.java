package com.yuyu.fishagent.card.controller;

import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.card.dto.ReviewAnswerRequest;
import com.yuyu.fishagent.card.dto.ReviewAnswerResponse;
import com.yuyu.fishagent.card.dto.ReviewQueueResponse;
import com.yuyu.fishagent.card.dto.ReviewStatsResponse;
import com.yuyu.fishagent.card.service.CardReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 卡片复习 API：面向前端复习模式，不承载卡片 CRUD。
 */
@RestController
@RequestMapping("/api/card/review")
@RequiredArgsConstructor
public class CardReviewController {

    private final CardReviewService reviewService;

    @GetMapping("/queue")
    public ReviewQueueResponse queue(@RequestParam(required = false) Long groupId) {
        return reviewService.getReviewQueue(requireUserId(), groupId);
    }

    @PostMapping("/answer")
    public ReviewAnswerResponse answer(@RequestBody ReviewAnswerRequest request) {
        return reviewService.submitAnswer(requireUserId(), request.cardId(), request.quality());
    }

    @GetMapping("/stats")
    public ReviewStatsResponse stats() {
        return reviewService.getReviewStats(requireUserId());
    }

    private static Long requireUserId() {
        Long userId = UserContextHolder.currentUserIdOrNull();
        if (userId == null) {
            throw new IllegalStateException("未登录");
        }
        return userId;
    }
}
