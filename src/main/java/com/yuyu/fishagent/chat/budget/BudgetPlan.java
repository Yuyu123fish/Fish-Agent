package com.yuyu.fishagent.chat.budget;

/**
 * Token budgets for each context section in priority order.
 *
 * @param exhausted {@code true} when P0/P1 already consume the input budget
 */
public record BudgetPlan(
        int userTokens,
        int instructionTokens,
        int windowBudget,
        int ragBudget,
        int summaryBudget,
        int excerptBudget,
        int stateBudget,
        int inputBudget,
        boolean exhausted
) {

    public static BudgetPlan exhausted(int userTokens, int instructionTokens, int inputBudget) {
        return new BudgetPlan(userTokens, instructionTokens, 0, 0, 0, 0, 0, inputBudget, true);
    }
}
