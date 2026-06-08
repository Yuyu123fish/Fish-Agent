package com.yuyu.fishagent.rag.pipeline.expand;

import com.yuyu.fishagent.common.trace.MdcAsync;
import com.yuyu.fishagent.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HyDE：生成假设性答案作为向量检索锚点。
 * <p>该组件只返回可选文本，不关心 ES 与召回编排；关闭、失败或超时时返回 null，由调用方回退原 query。</p>
 */
@Slf4j
public final class RagHydeService {

    private static final String SYSTEM_INSTRUCTION = """
            你是知识助手。针对用户问题，直接写出一段简洁、专业的假设性答案，作为检索锚点。
            只输出答案正文本身，不要复述问题、不要寒暄、不要 Markdown 标题。
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final RagProperties ragProperties;

    public RagHydeService(ObjectProvider<ChatModel> chatModelProvider, RagProperties ragProperties) {
        this.chatModelProvider = chatModelProvider;
        this.ragProperties = ragProperties;
    }

    public String generate(String query) {
        if (!ragProperties.getHyde().isEnabled() || query == null || query.isBlank()) {
            return null;
        }
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            return null;
        }
        long timeoutMs = Math.max(1, ragProperties.getHyde().getTimeoutMs());
        try {
            String text = MdcAsync.mdcSupplyAsync(() -> {
                Prompt prompt = new Prompt(
                        new SystemMessage(SYSTEM_INSTRUCTION),
                        new UserMessage(query.trim()));
                return model.call(prompt).getResult().getOutput().getText();
            }).get(timeoutMs, TimeUnit.MILLISECONDS);
            return text == null || text.isBlank() ? null : text.trim();
        } catch (TimeoutException e) {
            log.warn("[RagHydeService] HyDE 生成超时（{}ms），回退原 query", timeoutMs);
            return null;
        } catch (Exception e) {
            log.warn("[RagHydeService] HyDE 生成失败，回退原 query: {}", e.getMessage());
            return null;
        }
    }
}
