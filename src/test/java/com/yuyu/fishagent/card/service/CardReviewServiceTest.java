package com.yuyu.fishagent.card.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CardReviewServiceTest {

    @Test
    void firstSuccessfulReviewSchedulesOneDayLater() {
        CardReviewService.ReviewSchedule schedule = CardReviewService.scheduleNext(5, 2.5, 0, 0);

        assertThat(schedule.intervalDays()).isEqualTo(1);
        assertThat(schedule.repetition()).isEqualTo(1);
        assertThat(schedule.easinessFactor()).isCloseTo(2.6, within(0.0001));
    }

    @Test
    void secondSuccessfulReviewSchedulesSixDaysLater() {
        CardReviewService.ReviewSchedule schedule = CardReviewService.scheduleNext(5, 2.6, 1, 1);

        assertThat(schedule.intervalDays()).isEqualTo(6);
        assertThat(schedule.repetition()).isEqualTo(2);
        assertThat(schedule.easinessFactor()).isCloseTo(2.7, within(0.0001));
    }

    @Test
    void forgottenReviewResetsRepetitionAndKeepsMinimumEasinessFactor() {
        CardReviewService.ReviewSchedule schedule = CardReviewService.scheduleNext(0, 1.35, 10, 4);

        assertThat(schedule.intervalDays()).isEqualTo(1);
        assertThat(schedule.repetition()).isZero();
        assertThat(schedule.easinessFactor()).isEqualTo(1.3);
    }

    @Test
    void fuzzyReview_firstReview_schedulesOneDay() {
        CardReviewService.ReviewSchedule schedule = CardReviewService.scheduleNext(3, 2.5, 0, 0);

        assertThat(schedule.intervalDays()).isEqualTo(1);
        assertThat(schedule.repetition()).isEqualTo(1);
        // EF: 2.5 + 0.1 - (5-3)*(0.08 + (5-3)*0.02) = 2.5 + 0.1 - 2*0.12 = 2.5 + 0.1 - 0.24 = 2.36
        assertThat(schedule.easinessFactor()).isCloseTo(2.36, within(0.0001));
    }

    @Test
    void thirdSuccessfulReview_usesEfMultiplier() {
        CardReviewService.ReviewSchedule schedule = CardReviewService.scheduleNext(5, 2.7, 6, 2);

        assertThat(schedule.intervalDays()).isEqualTo((int) Math.round(6 * 2.7));
        assertThat(schedule.repetition()).isEqualTo(3);
    }

    @Test
    void easinessFactorNeverDropsBelowMinimum() {
        // quality=0 with low ef should clamp to 1.3
        CardReviewService.ReviewSchedule schedule = CardReviewService.scheduleNext(0, 1.3, 5, 3);

        assertThat(schedule.easinessFactor()).isEqualTo(1.3);
        assertThat(schedule.intervalDays()).isEqualTo(1);
        assertThat(schedule.repetition()).isZero();
    }

    @Test
    void qualityClampedToValidRange() {
        // quality=-1 should be clamped to 0 (forgot)
        CardReviewService.ReviewSchedule schedule = CardReviewService.scheduleNext(-1, 2.5, 6, 2);

        assertThat(schedule.repetition()).isZero();
        assertThat(schedule.intervalDays()).isEqualTo(1);

        // quality=10 should be clamped to 5 (known)
        CardReviewService.ReviewSchedule schedule2 = CardReviewService.scheduleNext(10, 2.5, 0, 0);
        assertThat(schedule2.intervalDays()).isEqualTo(1);
        assertThat(schedule2.repetition()).isEqualTo(1);
    }
}
