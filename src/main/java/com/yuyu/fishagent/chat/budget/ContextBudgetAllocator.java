package com.yuyu.fishagent.chat.budget;

import com.yuyu.fishagent.common.util.TokenEstimator;

/**
 * Allocates the model input window across context sections.
 * <p>
 * P0 user input and P1 system instruction are reserved first. Remaining budget
 * is split by fixed weights so behavior stays predictable and easy to tune.
 * </p>
 */
public class ContextBudgetAllocator {

    private static final int MIN_INPUT_BUDGET = 1_000;

    /**
     * Reserve for the {@code "\n\n---\n"} separators appended between dynamic
     * system sections. Without this, the per-section budgets would sum exactly
     * to {@code remaining} and the final guard could trip on separator overhead
     * alone, forcing an unnecessary emergency trim even though the model window
     * still has headroom. 64 tokens comfortably covers up to ~5 separators.
     */
    private static final int SECTION_SEPARATOR_RESERVE = 64;

    private final int inputBudget;

    public ContextBudgetAllocator(int contextWindowTokens, int outputReserve, double safetyRatio) {
        int boundedWindow = Math.max(0, contextWindowTokens);
        int boundedReserve = Math.max(0, outputReserve);
        double boundedSafetyRatio = Math.clamp(safetyRatio, 0.0, 0.9);
        int safeBudget = (int) (boundedWindow * (1 - boundedSafetyRatio));
        this.inputBudget = Math.max(MIN_INPUT_BUDGET, safeBudget - boundedReserve);
    }

    /**
     * Allocate budget for optional sections after required user/system text.
     */
    public BudgetPlan allocate(BudgetRequest request) {
        int userTokens = TokenEstimator.estimate(request == null ? null : request.userMessage());
        int instructionTokens = TokenEstimator.estimate(request == null ? null : request.instruction());
        // 预留段间分隔符开销后再按权重切分，保证五段预算之和留出分隔符空间。
        int remaining = inputBudget - userTokens - instructionTokens - SECTION_SEPARATOR_RESERVE;
        if (remaining <= 0) {
            return BudgetPlan.exhausted(userTokens, instructionTokens, inputBudget);
        }

        int windowBudget = (int) (remaining * 0.40);
        int ragBudget = (int) (remaining * 0.25);
        int summaryBudget = (int) (remaining * 0.20);
        int excerptBudget = (int) (remaining * 0.10);
        int stateBudget = remaining - windowBudget - ragBudget - summaryBudget - excerptBudget;
        return new BudgetPlan(
                userTokens,
                instructionTokens,
                windowBudget,
                ragBudget,
                summaryBudget,
                excerptBudget,
                stateBudget,
                inputBudget,
                false
        );
    }
}
