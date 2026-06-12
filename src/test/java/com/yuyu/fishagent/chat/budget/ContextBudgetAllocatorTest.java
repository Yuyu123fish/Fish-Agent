package com.yuyu.fishagent.chat.budget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetAllocatorTest {

    @Test
    void allocatesRemainingBudgetByConfiguredPriorityWeights() {
        ContextBudgetAllocator allocator = new ContextBudgetAllocator(10_000, 1_000, 0.2);

        BudgetPlan plan = allocator.allocate(new BudgetRequest("你好世界", "system prompt"));

        assertThat(plan.inputBudget()).isEqualTo(7_000);
        assertThat(plan.userTokens()).isEqualTo(3);
        assertThat(plan.instructionTokens()).isEqualTo(4);
        // remaining = 7000 - 3 - 4 - 64(段间分隔符预留) = 6929
        assertThat(plan.windowBudget()).isEqualTo(2_771);
        assertThat(plan.ragBudget()).isEqualTo(1_732);
        assertThat(plan.summaryBudget()).isEqualTo(1_385);
        assertThat(plan.excerptBudget()).isEqualTo(692);
        assertThat(plan.stateBudget()).isEqualTo(349);
        assertThat(plan.exhausted()).isFalse();
    }

    @Test
    void returnsExhaustedPlanWhenRequiredMessagesConsumeBudget() {
        ContextBudgetAllocator allocator = new ContextBudgetAllocator(2_000, 500, 0.2);
        String veryLongUserMessage = "中".repeat(3_000);

        BudgetPlan plan = allocator.allocate(new BudgetRequest(veryLongUserMessage, "system"));

        assertThat(plan.inputBudget()).isEqualTo(1_100);
        assertThat(plan.exhausted()).isTrue();
        assertThat(plan.windowBudget()).isZero();
        assertThat(plan.ragBudget()).isZero();
        assertThat(plan.summaryBudget()).isZero();
        assertThat(plan.excerptBudget()).isZero();
        assertThat(plan.stateBudget()).isZero();
    }

    @Test
    void sectionBudgetsLeaveHeadroomForSeparatorsBelowInputBudget() {
        // 五段预算之和必须小于 inputBudget - user - instruction，留出段间分隔符空间，
        // 否则最坏情况（各段同时撑满）会因分隔符开销触发不必要的 emergencyTrim。
        ContextBudgetAllocator allocator = new ContextBudgetAllocator(10_000, 1_000, 0.2);
        BudgetPlan plan = allocator.allocate(new BudgetRequest("hi", "sys"));

        int sectionSum = plan.windowBudget() + plan.ragBudget()
                + plan.summaryBudget() + plan.excerptBudget() + plan.stateBudget();
        int headroom = plan.inputBudget() - plan.userTokens() - plan.instructionTokens() - sectionSum;

        assertThat(headroom).isPositive();
        assertThat(sectionSum).isLessThan(plan.inputBudget() - plan.userTokens() - plan.instructionTokens());
    }
}
