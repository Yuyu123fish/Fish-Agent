package com.yuyu.fishagent.chat.budget;

/**
 * Required high-priority text used to reserve prompt budget before optional
 * context sections are rendered.
 */
public record BudgetRequest(String userMessage, String instruction) {
}
