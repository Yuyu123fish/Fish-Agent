package com.yuyu.fishagent.agent.memory.longterm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆提取结果解析器。
 * <p>模型只能返回 long_term_facts 数组；解析失败时由上层决定是否跳过录入。</p>
 */
@Component
@RequiredArgsConstructor
public class LongTermMemoryResponseParser {

    private final ObjectMapper objectMapper;

    /**
     * 解析长期事实 JSON，并过滤空字符串事实。
     *
     * @param rawText 模型原始输出
     * @return 可写入长期记忆的事实列表
     */
    public List<String> parseFacts(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("long-term memory model output cannot be empty");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(stripCodeFence(rawText));
        } catch (Exception e) {
            throw new IllegalArgumentException("long-term memory model output is not valid JSON", e);
        }

        JsonNode factsNode = root.get("long_term_facts");
        if (!root.isObject() || factsNode == null || !factsNode.isArray()) {
            throw new IllegalArgumentException("long-term memory output must contain array long_term_facts");
        }

        List<String> facts = new ArrayList<>();
        for (JsonNode factNode : factsNode) {
            if (!factNode.isTextual()) {
                throw new IllegalArgumentException("long_term_facts must only contain strings");
            }
            String fact = factNode.asText().trim();
            if (!fact.isEmpty()) {
                facts.add(fact);
            }
        }
        return facts;
    }

    /**
     * 兼容模型偶发输出 markdown 代码块的情况。
     */
    private String stripCodeFence(String rawText) {
        String text = rawText.trim();
        if (!text.startsWith("```")) {
            return text;
        }

        int firstLineEnd = text.indexOf('\n');
        int lastFenceStart = text.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFenceStart <= firstLineEnd) {
            return text;
        }
        return text.substring(firstLineEnd + 1, lastFenceStart).trim();
    }
}
