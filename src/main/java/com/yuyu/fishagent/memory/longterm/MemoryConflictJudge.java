package com.yuyu.fishagent.memory.longterm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 长期记忆相似事实冲突判定器。
 *
 * <p>只负责把“新事实 + 单条旧事实”判为 SAME / CONFLICT / NEITHER；写入、覆盖和 ES 更新由存储层处理。
 * 模型不可用或异常时返回 NEITHER，优先避免误删用户的新事实。</p>
 */
@Slf4j
@Component
public class MemoryConflictJudge {

    private static final String SYSTEM_PROMPT = """
            你是长期记忆事实冲突判定器。比较一条新事实与一条旧事实，只能输出 SAME、CONFLICT、NEITHER 三者之一。
            SAME：两条事实表达同一含义或新事实只是旧事实的改写。
            CONFLICT：两条事实针对同一主体/属性给出互斥值，不能同时为真。
            NEITHER：相关但可共存、主体不同、属性不同，或证据不足。
            严禁解释，严禁输出额外文字。
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;

    public MemoryConflictJudge(@Qualifier("memoryChatModel") ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    public Verdict judge(String candidateFact, SimilarFact existingFact) {
        if (candidateFact == null || candidateFact.isBlank() || existingFact == null
                || existingFact.content() == null || existingFact.content().isBlank()) {
            return Verdict.NEITHER;
        }
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            return Verdict.NEITHER;
        }
        try {
            Prompt prompt = new Prompt(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage("""
                            旧事实：
                            %s

                            新事实：
                            %s
                            """.formatted(existingFact.content().trim(), candidateFact.trim())));
            String raw = model.call(prompt).getResult().getOutput().getText();
            return parseVerdict(raw);
        } catch (Exception e) {
            log.warn("[MemoryConflictJudge] 判定失败，按 NEITHER 处理: {}", e.getMessage());
            return Verdict.NEITHER;
        }
    }

    static Verdict parseVerdict(String raw) {
        if (raw == null || raw.isBlank()) {
            return Verdict.NEITHER;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (Verdict verdict : Verdict.values()) {
            if (normalized.equals(verdict.name()) || normalized.contains("\"VERDICT\":\"" + verdict.name() + "\"")) {
                return verdict;
            }
        }
        return Verdict.NEITHER;
    }

    public enum Verdict {
        SAME,
        CONFLICT,
        NEITHER
    }
}
