package com.yuyu.fishagent.memory;

import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.memory.compress.MemoryPromptBuilder;
import com.yuyu.fishagent.memory.compress.MemoryResponseParser;
import com.yuyu.fishagent.memory.shortterm.ShortTermMemoryStore;
import com.yuyu.fishagent.memory.shortterm.ShortTermMemorySnapshot;
import com.yuyu.fishagent.memory.shortterm.StructuredSummary;
import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.memory.dto.MemoryCompressionRequest;
import com.yuyu.fishagent.memory.dto.MemoryCompressionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆压缩编排服务。
 * <p>只编排“模型压缩 -> JSON 解析 -> 短期记忆写入”流程。
 * 长期记忆由独立的主动录入服务处理，避免摘要任务造成 ES 冗余。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryCompressionService {

    /** 使用记忆链路专用模型（见 {@code fish.memory.chat.*} 与 {@code memoryChatModel} Bean）。 */
    @Qualifier("memoryChatModel")
    private final ChatModel chatModel;
    private final MemoryPromptBuilder promptBuilder;
    private final MemoryResponseParser responseParser;
    private final ShortTermMemoryStore shortTermMemoryStore;
    private final MemoryProperties properties;

    /**
     * 执行一次短期记忆压缩：调用模型生成摘要 JSON，再写入 Redis。
     *
     * @param request 包含 sessionId 和完整对话历史的压缩请求
     * @return 模型生成并通过格式校验的记忆压缩结果
     */
    public MemoryCompressionResult compress(MemoryCompressionRequest request) {
        validate(request);

        List<ChatMessageDTO> chatHistory = request.getChatHistory();
        log.debug("[MemoryCompressionService] 开始记忆压缩 sid={}, historySize={}",
                request.getSessionId(), chatHistory.size());
        // 模型输出必须先通过严格解析，避免脏数据进入 Redis / ES。
        Prompt prompt = promptBuilder.build(chatHistory);
        ChatResponse response = chatModel.call(prompt);
        String output = response.getResult().getOutput().getText();
        MemoryCompressionResult result = responseParser.parse(output);
        log.debug("[MemoryCompressionService] 记忆压缩完成 sid={}, summaryLen={}, factsCount={}",
                request.getSessionId(),
                result.getShortTermSummary() == null ? 0 : result.getShortTermSummary().length(),
                result.getLongTermFacts() == null ? 0 : result.getLongTermFacts().size());

        saveShortTermMemory(request.getSessionId(), result, chatHistory);
        log.debug("[MemoryCompressionService] 短期摘要流程不写入 ES sid={}", request.getSessionId());
        return result;
    }

    /**
     * 截取最近 N 条消息作为滑动窗口，避免下一轮对话上下文无限增长。
     */
    static List<ChatMessageDTO> recentMessages(List<ChatMessageDTO> chatHistory, int windowSize) {
        if (chatHistory == null || chatHistory.isEmpty() || windowSize <= 0) {
            return List.of();
        }
        int fromIndex = Math.max(0, chatHistory.size() - windowSize);
        return new ArrayList<>(chatHistory.subList(fromIndex, chatHistory.size()));
    }

    /**
     * 校验压缩请求的最小必需字段，避免空会话写入记忆系统。
     */
    private void validate(MemoryCompressionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be empty");
        }
        if (request.getChatHistory() == null || request.getChatHistory().isEmpty()) {
            throw new IllegalArgumentException("chatHistory cannot be empty");
        }
    }

    /**
     * 保存短期记忆。失败只记录日志，让压缩接口仍能返回模型结果。
     */
    private void saveShortTermMemory(String sessionId, MemoryCompressionResult result, List<ChatMessageDTO> chatHistory) {
        try {
            List<ChatMessageDTO> recentMessages = recentMessages(chatHistory, properties.getShortTermWindowSize());
            log.debug("[MemoryCompressionService] 写入短期记忆 sid={}, summaryLen={}, recentMessages={}",
                    sessionId,
                    result.getShortTermSummary() == null ? 0 : result.getShortTermSummary().length(),
                    recentMessages.size());
            shortTermMemoryStore.save(sessionId, new ShortTermMemorySnapshot(
                    result.getShortTermSummary(),
                    recentMessages,
                    lastMessageCreatedAt(chatHistory),
                    chatHistory.size()
            ));
        } catch (Exception e) {
            log.warn("[MemoryCompressionService] 短期记忆写入失败 sid={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 结构化压缩：支持增量/校准两种模式。每 3 次增量后触发一次全量校准，降低摘要漂移。
     *
     * @param sessionId 会话 ID
     * @param currentSummary 当前结构化摘要，首次压缩可为空
     * @param newMessages 窗口外新增消息，增量模式使用
     * @param fullHistory 全量历史，校准模式和窗口裁剪使用
     * @param incrementalCount 当前增量次数
     * @return 模型解析后的结构化压缩结果
     */
    public MemoryResponseParser.StructuredCompressionResult compressStructured(
            String sessionId,
            StructuredSummary currentSummary,
            List<ChatMessageDTO> newMessages,
            List<ChatMessageDTO> fullHistory,
            int incrementalCount) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be empty");
        }
        List<ChatMessageDTO> safeFull = fullHistory == null ? List.of() : fullHistory;
        if (safeFull.isEmpty()) {
            throw new IllegalArgumentException("fullHistory cannot be empty");
        }

        boolean calibration = incrementalCount >= 3;
        Prompt prompt = calibration
                ? promptBuilder.buildCalibration(safeFull, properties.getShortTermWindowSize())
                : promptBuilder.buildIncremental(currentSummary,
                newMessages == null ? List.of() : newMessages,
                properties.getShortTermWindowSize());

        log.debug("[MemoryCompressionService] 结构化压缩开始 sid={}, mode={}, incCount={}",
                sessionId, calibration ? "校准" : "增量", incrementalCount);
        ChatResponse response = chatModel.call(prompt);
        String output = response.getResult().getOutput().getText();
        MemoryResponseParser.StructuredCompressionResult result = responseParser.parseStructured(output);

        int newCount = calibration ? 0 : incrementalCount + 1;
        ShortTermMemorySnapshot snapshot = new ShortTermMemorySnapshot(
                result.summary(),
                recentMessages(safeFull, properties.getShortTermWindowSize()),
                result.keyExcerpts(),
                newCount,
                lastMessageCreatedAt(safeFull),
                safeFull.size()
        );
        shortTermMemoryStore.save(sessionId, snapshot);
        log.debug("[MemoryCompressionService] 结构化压缩完成 sid={}, mode={}, newCount={}, topics={}, excerpts={}",
                sessionId,
                calibration ? "校准" : "增量",
                newCount,
                result.summary().activeTopics() == null ? 0 : result.summary().activeTopics().size(),
                result.keyExcerpts() == null ? 0 : result.keyExcerpts().size());
        return result;
    }

    /**
     * 记录压缩覆盖到的最后一条消息时间，作为后续增量维护游标。
     */
    static long lastMessageCreatedAt(List<ChatMessageDTO> history) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessageDTO message = history.get(i);
            if (message != null && message.getCreatedAt() > 0) {
                return message.getCreatedAt();
            }
        }
        return 0;
    }

}
