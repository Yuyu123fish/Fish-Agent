package com.yuyu.fishagent.memory.compress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.memory.shortterm.KeyExcerpt;
import com.yuyu.fishagent.memory.shortterm.StructuredSummary;
import com.yuyu.fishagent.memory.shortterm.TopicSegment;
import com.yuyu.fishagent.memory.shortterm.UserSignals;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 严格解析模型返回的记忆压缩 JSON。
 * <p>模型输出不可信，解析层单独存在，避免把格式校验散落在业务流程里。</p>
 */
@Component
@RequiredArgsConstructor
public class MemoryResponseParser {

    private final ObjectMapper objectMapper;

    /**
     * 结构化压缩结果。Stage 3 会把 {@code agentStateNode} 解析为强类型 Agent 状态。
     */
    public record StructuredCompressionResult(
            StructuredSummary summary,
            List<KeyExcerpt> keyExcerpts,
            JsonNode agentStateNode
    ) {
    }

    /**
     * 解析增量/校准模式的结构化输出。
     *
     * @param rawText 模型原始输出，允许包裹在 markdown 代码块中
     * @return 结构化摘要、关键片段和原始 agent_state 节点
     */
    public StructuredCompressionResult parseStructured(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("structured compression output cannot be empty");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(stripCodeFence(rawText));
        } catch (Exception e) {
            throw new IllegalArgumentException("structured compression output is not valid JSON", e);
        }

        JsonNode summaryNode = root.get("structured_summary");
        if (!root.isObject() || summaryNode == null || !summaryNode.isObject()) {
            throw new IllegalArgumentException("structured compression output must contain structured_summary object");
        }

        JsonNode excerptsNode = root.get("key_excerpts");
        return new StructuredCompressionResult(
                parseStructuredSummary(summaryNode),
                excerptsNode == null ? List.of() : parseKeyExcerpts(excerptsNode),
                root.get("agent_state")
        );
    }

    /**
     * 兼容模型偶发输出 ```json 代码块的情况，但解析层仍要求内部必须是合法 JSON。
     * 提取首尾 ``` 之间的内容，忽略第一行可能存在的 "json" 标记
     */
    private String stripCodeFence(String rawText) {
        String text = rawText.trim();
        // 不是代码块格式，直接返回
        if (!text.startsWith("```")) {
            return text;
        }

        // 找到第一行结束位置和最后一个 ``` 位置
        int firstLineEnd = text.indexOf('\n');
        int lastFenceStart = text.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFenceStart <= firstLineEnd) {
            return text;
        }
        // 提取中间的 JSON 内容
        return text.substring(firstLineEnd + 1, lastFenceStart).trim();
    }

    private StructuredSummary parseStructuredSummary(JsonNode node) {
        List<TopicSegment> topics = new ArrayList<>();
        JsonNode topicsNode = node.get("activeTopics");
        if (topicsNode != null && topicsNode.isArray()) {
            for (JsonNode t : topicsNode) {
                topics.add(new TopicSegment(
                        t.path("topic").asText(""),
                        t.path("status").asText("ACTIVE"),
                        t.path("summary").asText("")
                ));
            }
        }

        Map<String, List<String>> entities = new HashMap<>();
        JsonNode entitiesNode = node.get("keyEntities");
        if (entitiesNode != null && entitiesNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = entitiesNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                entities.put(entry.getKey(), parseStringList(entry.getValue()));
            }
        }

        UserSignals signals = new UserSignals("", "", List.of());
        JsonNode signalsNode = node.get("userSignals");
        if (signalsNode != null && signalsNode.isObject()) {
            signals = new UserSignals(
                    signalsNode.path("expertise").asText(""),
                    signalsNode.path("communicationStyle").asText(""),
                    parseStringList(signalsNode.get("observedPreferences"))
            );
        }

        return new StructuredSummary(
                topics,
                entities,
                parseStringList(node.get("pendingIntents")),
                signals
        );
    }

    private List<KeyExcerpt> parseKeyExcerpts(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<KeyExcerpt> excerpts = new ArrayList<>();
        for (JsonNode e : node) {
            excerpts.add(new KeyExcerpt(
                    e.path("turnIndex").asInt(0),
                    e.path("role").asText("user"),
                    e.path("content").asText(""),
                    e.path("reason").asText("")
            ));
        }
        return excerpts;
    }

    private List<String> parseStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                String value = item.asText().trim();
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
        }
        return result;
    }
}
