package com.yuyu.fishagent.chat.budget;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.common.util.TokenEstimator;

import java.util.List;

/**
 * Trims replayable chat history by token budget while preserving recency.
 */
public final class ContextWindowTrimmer {

    private ContextWindowTrimmer() {
    }

    /**
     * Keep the newest complete messages that fit within {@code budget}.
     * <p>
     * If the retained slice would begin with an assistant message, the boundary
     * advances one step so the model does not see an orphaned assistant answer
     * without the user's preceding turn.
     * </p>
     */
    public static List<ChatMessageDTO> trimMessagesByBudget(List<ChatMessageDTO> messages, int budget) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        if (budget <= 0) {
            return List.of();
        }
        int used = 0;
        int cutIndex = messages.size();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDTO message = messages.get(i);
            int messageTokens = TokenEstimator.estimate(message == null ? null : message.getContent());
            if (used + messageTokens > budget) {
                break;
            }
            used += messageTokens;
            cutIndex = i;
        }
        if (cutIndex >= messages.size()) {
            return List.of();
        }
        if (cutIndex > 0 && !"user".equals(messages.get(cutIndex).getRole())) {
            cutIndex++;
        }
        return List.copyOf(messages.subList(cutIndex, messages.size()));
    }
}
