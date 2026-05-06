package com.yuyu.fishagent.service;

import com.yuyu.fishagent.agent.memory.longterm.LongTermMemoryFactSanitizer;
import com.yuyu.fishagent.agent.memory.longterm.LongTermMemoryPromptBuilder;
import com.yuyu.fishagent.agent.memory.longterm.LongTermMemoryResponseParser;
import com.yuyu.fishagent.agent.memory.longterm.LongTermMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 长期记忆主动录入服务。
 * <p>每轮用户输入后独立判断是否存在稳定事实；不依赖短期摘要触发阈值。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LongTermMemoryIngestionService {

    /** 使用记忆链路专用模型（见 {@code fish.memory.chat.*} 与 {@code memoryChatModel} Bean）。 */
    @Qualifier("memoryChatModel")
    private final ChatModel chatModel;
    private final LongTermMemoryPromptBuilder promptBuilder;
    private final LongTermMemoryResponseParser responseParser;
    private final LongTermMemoryStore longTermMemoryStore;

    /**
     * 根据当前用户输入主动录入长期事实。
     *
     * @param userId    用户主键（异步任务入口传入，可为 {@code null} 则不入库）
     * @param sessionId 会话 ID
     * @param userInput 当前轮用户输入
     */
    public void ingestFromUserInput(Long userId, String sessionId, String userInput) {
        if (userId == null) {
            log.debug("[LongTermMemoryIngestionService] userId 为空，跳过长期记忆录入 sid={}", sessionId);
            return;
        }
        if (userInput == null || userInput.isBlank()) {
            log.debug("[LongTermMemoryIngestionService] 用户输入为空，跳过长期记忆录入 sid={}", sessionId);
            return;
        }

        try {
            log.debug("[LongTermMemoryIngestionService] 开始长期事实判断 sid={}, inputLen={}",
                    sessionId, userInput.length());
            Prompt prompt = promptBuilder.build(userInput);
            ChatResponse response = chatModel.call(prompt);
            String output = response.getResult().getOutput().getText();
            List<String> facts = responseParser.parseFacts(output);
            List<String> toSave = LongTermMemoryFactSanitizer.forIndexing(facts);
            if (toSave.isEmpty()) {
                log.debug("[LongTermMemoryIngestionService] 未提取到可写入的长期事实（或均被产品说明过滤器剔除）sid={}, rawCount={}",
                        sessionId, facts.size());
                return;
            }

            log.debug("[LongTermMemoryIngestionService] 提取到长期事实 sid={}, factsCount={}, facts={}",
                    sessionId, toSave.size(), toSave);
            longTermMemoryStore.saveFacts(userId, sessionId, toSave);
        } catch (Exception e) {
            log.warn("[LongTermMemoryIngestionService] 长期记忆主动录入失败 sid={}: {}", sessionId, e.getMessage());
        }
    }
}
