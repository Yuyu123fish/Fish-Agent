package com.yuyu.fishagent.card.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.fishagent.card.dto.ReviewAnswerResponse;
import com.yuyu.fishagent.card.dto.ReviewCardVO;
import com.yuyu.fishagent.card.dto.ReviewInfoDTO;
import com.yuyu.fishagent.card.dto.ReviewQueueResponse;
import com.yuyu.fishagent.card.dto.ReviewStatsResponse;
import com.yuyu.fishagent.card.entity.CardReviewRecord;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import com.yuyu.fishagent.card.mapper.CardReviewRecordMapper;
import com.yuyu.fishagent.card.mapper.KnowledgeCardMapper;
import com.yuyu.fishagent.common.cache.CacheConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * SM-2 间隔重复服务：只负责复习队列、评分调度和统计，卡片 CRUD 仍由 KnowledgeCardService 负责。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardReviewService {

    private static final double DEFAULT_EASINESS_FACTOR = 2.5;
    private static final double MIN_EASINESS_FACTOR = 1.3;

    private final CardReviewRecordMapper reviewRecordMapper;
    private final KnowledgeCardMapper cardMapper;
    private final CardGroupService cardGroupService;

    public record ReviewSchedule(double easinessFactor, int intervalDays, int repetition) {
    }

    /**
     * SM-2 纯计算。调用方负责传入上一条记录的 ef/interval/repetition，并持久化返回快照。
     */
    public static ReviewSchedule scheduleNext(int quality, double easinessFactor, int intervalDays, int repetition) {
        int safeQuality = Math.max(0, Math.min(5, quality));
        double safeEf = easinessFactor <= 0 ? DEFAULT_EASINESS_FACTOR : easinessFactor;
        int nextInterval;
        int nextRepetition;
        if (safeQuality >= 3) {
            if (repetition <= 0) {
                nextInterval = 1;
            } else if (repetition == 1) {
                nextInterval = 6;
            } else {
                nextInterval = Math.max(1, (int) Math.round(intervalDays * safeEf));
            }
            nextRepetition = repetition + 1;
        } else {
            nextInterval = 1;
            nextRepetition = 0;
        }
        double nextEf = safeEf + 0.1 - (5 - safeQuality) * (0.08 + (5 - safeQuality) * 0.02);
        return new ReviewSchedule(Math.max(MIN_EASINESS_FACTOR, nextEf), nextInterval, nextRepetition);
    }

    @Transactional(readOnly = true)
    public ReviewQueueResponse getReviewQueue(Long userId, Long groupId) {
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeCard> candidates = selectConfirmedCards(userId, groupId);
        List<KnowledgeCard> dueCards = new ArrayList<>();
        List<KnowledgeCard> newCards = new ArrayList<>();
        for (KnowledgeCard card : candidates) {
            if (card.getReviewNextAt() == null) {
                newCards.add(card);
            } else if (!card.getReviewNextAt().isAfter(now)) {
                dueCards.add(card);
            }
        }
        List<ReviewCardVO> cards = new ArrayList<>();
        dueCards.forEach(card -> cards.add(toReviewCard(userId, card)));
        newCards.forEach(card -> cards.add(toReviewCard(userId, card)));
        return new ReviewQueueResponse(cards, dueCards.size(), newCards.size());
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.CARD_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_STATS, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_RELATIONS, allEntries = true)
    })
    @Transactional
    public ReviewAnswerResponse submitAnswer(Long userId, Long cardId, int quality) {
        KnowledgeCard card = findOwnedConfirmedCard(userId, cardId);
        CardReviewRecord latest = reviewRecordMapper.selectLatestByCardAndUser(cardId, userId);
        ReviewSchedule schedule = scheduleNext(
                quality,
                latest == null ? DEFAULT_EASINESS_FACTOR : latest.getEasinessFactor(),
                latest == null ? 0 : safeInt(latest.getIntervalDays()),
                latest == null ? 0 : safeInt(latest.getRepetition())
        );
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextReviewAt = now.plusDays(schedule.intervalDays());

        CardReviewRecord record = new CardReviewRecord();
        record.setCardId(cardId);
        record.setUserId(userId);
        record.setQuality(quality);
        record.setEasinessFactor(schedule.easinessFactor());
        record.setIntervalDays(schedule.intervalDays());
        record.setRepetition(schedule.repetition());
        record.setReviewedAt(now);
        record.setNextReviewAt(nextReviewAt);
        reviewRecordMapper.insert(record);

        // 冗余字段服务列表筛选和详情展示，复习记录仍是事实来源。
        cardMapper.update(null, Wrappers.<KnowledgeCard>lambdaUpdate()
                .eq(KnowledgeCard::getId, cardId)
                .set(KnowledgeCard::getReviewNextAt, nextReviewAt)
                .setSql("review_count = review_count + 1")
                .set(KnowledgeCard::getLastReviewedAt, now));

        int remainingDue = Math.toIntExact(countDueCards(userId, LocalDateTime.now()));
        return new ReviewAnswerResponse(nextReviewAt, schedule.intervalDays(), schedule.easinessFactor(), remainingDue);
    }

    @Transactional(readOnly = true)
    public ReviewStatsResponse getReviewStats(Long userId) {
        List<KnowledgeCard> confirmedCards = selectConfirmedCards(userId, null);
        int totalCards = confirmedCards.size();
        int mastered = 0;
        int learning = 0;
        for (KnowledgeCard card : confirmedCards) {
            CardReviewRecord latest = reviewRecordMapper.selectLatestByCardAndUser(card.getId(), userId);
            if (latest == null) {
                continue;
            }
            if (safeInt(latest.getRepetition()) >= 3 && latest.getEasinessFactor() >= 2.0) {
                mastered++;
            } else {
                learning++;
            }
        }

        Map<String, Integer> calendar = dailyCounts(userId, LocalDate.now().minusDays(29).atStartOfDay());
        return new ReviewStatsResponse(
                totalCards,
                mastered,
                learning,
                Math.toIntExact(countDueCards(userId, LocalDate.now().plusDays(1).atStartOfDay().minusNanos(1))),
                countStreakDays(calendar),
                calendar,
                weeklyActivity(calendar)
        );
    }

    @Transactional(readOnly = true)
    public ReviewInfoDTO buildReviewInfo(Long userId, KnowledgeCard card) {
        CardReviewRecord latest = reviewRecordMapper.selectLatestByCardAndUser(card.getId(), userId);
        if (latest == null) {
            return new ReviewInfoDTO(
                    card.getReviewNextAt(),
                    safeInt(card.getReviewCount()),
                    card.getLastReviewedAt(),
                    DEFAULT_EASINESS_FACTOR,
                    0,
                    0
            );
        }
        return new ReviewInfoDTO(
                latest.getNextReviewAt(),
                safeInt(card.getReviewCount()),
                card.getLastReviewedAt(),
                latest.getEasinessFactor(),
                safeInt(latest.getIntervalDays()),
                safeInt(latest.getRepetition())
        );
    }

    private List<KnowledgeCard> selectConfirmedCards(Long userId, Long groupId) {
        var wrapper = Wrappers.<KnowledgeCard>lambdaQuery()
                .eq(KnowledgeCard::getUserId, userId)
                .eq(KnowledgeCard::getStatus, KnowledgeCard.STATUS_CONFIRMED)
                .orderByAsc(KnowledgeCard::getReviewNextAt)
                .orderByDesc(KnowledgeCard::getCreatedAt);
        if (groupId != null && groupId > 0) {
            wrapper.eq(KnowledgeCard::getGroupId, groupId);
        }
        return cardMapper.selectList(wrapper);
    }

    private ReviewCardVO toReviewCard(Long userId, KnowledgeCard card) {
        return new ReviewCardVO(
                card.getId(),
                card.getTitle(),
                card.getContent(),
                card.getKeywords() == null ? List.of() : card.getKeywords(),
                card.getCardType(),
                groupPath(card),
                buildReviewInfo(userId, card)
        );
    }

    private KnowledgeCard findOwnedConfirmedCard(Long userId, Long cardId) {
        if (cardId == null) {
            throw new IllegalArgumentException("cardId 不能为空");
        }
        KnowledgeCard card = cardMapper.selectById(cardId);
        if (card == null) {
            throw new ResponseStatusException(NOT_FOUND, "卡片不存在");
        }
        if (!Objects.equals(userId, card.getUserId())) {
            throw new ResponseStatusException(FORBIDDEN, "无权复习该卡片");
        }
        if (!KnowledgeCard.STATUS_CONFIRMED.equals(card.getStatus())) {
            throw new IllegalArgumentException("只能复习已确认卡片");
        }
        return card;
    }

    private long countDueCards(Long userId, LocalDateTime threshold) {
        return cardMapper.selectCount(Wrappers.<KnowledgeCard>lambdaQuery()
                .eq(KnowledgeCard::getUserId, userId)
                .eq(KnowledgeCard::getStatus, KnowledgeCard.STATUS_CONFIRMED)
                .isNotNull(KnowledgeCard::getReviewNextAt)
                .le(KnowledgeCard::getReviewNextAt, threshold));
    }

    private Map<String, Integer> dailyCounts(Long userId, LocalDateTime since) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 29; i >= 0; i--) {
            counts.put(LocalDate.now().minusDays(i).toString(), 0);
        }
        for (Map<String, Object> row : reviewRecordMapper.selectDailyCounts(userId, since)) {
            String day = String.valueOf(row.get("d"));
            Object count = row.get("c");
            if (counts.containsKey(day) && count instanceof Number n) {
                counts.put(day, n.intValue());
            }
        }
        return counts;
    }

    private int countStreakDays(Map<String, Integer> calendar) {
        int streak = 0;
        LocalDate cursor = LocalDate.now();
        while (calendar.getOrDefault(cursor.toString(), 0) > 0) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    private List<Integer> weeklyActivity(Map<String, Integer> calendar) {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        List<Integer> activity = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            activity.add(day.isAfter(today) ? 0 : calendar.getOrDefault(day.toString(), 0));
        }
        return activity;
    }

    private String groupPath(KnowledgeCard card) {
        if (card.getGroupId() != null) {
            String path = cardGroupService.getGroupPath(card.getGroupId()).stream()
                    .map(g -> g.getName())
                    .collect(Collectors.joining(" > "));
            if (!path.isBlank()) {
                return path;
            }
        }
        return card.getGroupName();
    }

    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
