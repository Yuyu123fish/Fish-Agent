package com.yuyu.fishagent.card.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.card.entity.CardRelation;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 提取 JSON 解析器：严格要求根对象包含 cards，单张无效卡片跳过，整体非 JSON 才抛错。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CardExtractResponseParser {

    private static final int MAX_CARDS = 12;
    private static final int MAX_KEYWORDS = 8;
    private static final int MAX_KEYWORD_LENGTH = 32;

    private final ObjectMapper objectMapper;

    public CardExtractionDraft parse(String rawText) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripCodeFence(rawText));
        } catch (Exception e) {
            throw new IllegalArgumentException("AI 提取结果不是合法 JSON", e);
        }
        JsonNode cardsNode = root.get("cards");
        if (!root.isObject() || cardsNode == null || !cardsNode.isArray()) {
            throw new IllegalArgumentException("AI 提取结果必须包含 cards 数组");
        }

        List<ExtractedCardDraft> cards = new ArrayList<>();
        Set<String> titles = new LinkedHashSet<>();
        for (JsonNode node : cardsNode) {
            if (cards.size() >= MAX_CARDS) {
                break;
            }
            ExtractedCardDraft card = parseCard(node);
            if (card == null) {
                continue;
            }
            if (!titles.add(card.title())) {
                log.debug("[CardExtractParser] 跳过重复标题卡片 title={}", card.title());
                continue;
            }
            cards.add(card);
        }

        Set<String> validTitles = new LinkedHashSet<>(titles);
        List<ExtractedRelationDraft> relations = parseRelations(root.get("relations"), validTitles);
        return new CardExtractionDraft(cards, relations);
    }

    private ExtractedCardDraft parseCard(JsonNode node) {
        String title = trimToNull(text(node, "title"));
        String content = trimToNull(text(node, "content"));
        if (title == null || content == null) {
            log.debug("[CardExtractParser] 跳过无效卡片：title/content 为空");
            return null;
        }
        // 内容过短说明质量不够（只有公式或一句话），跳过
        if (content.length() < 30) {
            log.debug("[CardExtractParser] 跳过低质量卡片（content < 30 字）：title={}", title);
            return null;
        }
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }
        String cardType = normalizeCardType(textAny(node, "card_type", "cardType"));
        String groupName = trimToNull(textAny(node, "group_name", "groupName"));
        return new ExtractedCardDraft(title, content, parseKeywords(node.get("keywords")), cardType, groupName);
    }

    private List<ExtractedRelationDraft> parseRelations(JsonNode relationsNode, Set<String> validTitles) {
        if (relationsNode == null || !relationsNode.isArray()) {
            return List.of();
        }
        List<ExtractedRelationDraft> relations = new ArrayList<>();
        for (JsonNode node : relationsNode) {
            String from = trimToNull(textAny(node, "from_title", "fromTitle"));
            String to = trimToNull(textAny(node, "to_title", "toTitle"));
            if (from == null || to == null || from.equals(to) || !validTitles.contains(from) || !validTitles.contains(to)) {
                continue;
            }
            relations.add(new ExtractedRelationDraft(
                    from,
                    to,
                    normalizeRelationType(textAny(node, "relation_type", "relationType")),
                    normalizeConfidence(node.get("confidence"))
            ));
        }
        return relations;
    }

    private static List<String> parseKeywords(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                continue;
            }
            String kw = trimToNull(item.asText());
            if (kw == null) {
                continue;
            }
            out.add(kw.length() > MAX_KEYWORD_LENGTH ? kw.substring(0, MAX_KEYWORD_LENGTH) : kw);
            if (out.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    private static String normalizeCardType(String raw) {
        String s = trimToNull(raw);
        if (KnowledgeCard.TYPE_TOPIC.equals(s)) {
            return KnowledgeCard.TYPE_TOPIC;
        }
        return KnowledgeCard.TYPE_CONCEPT;
    }

    private static String normalizeRelationType(String raw) {
        String s = trimToNull(raw);
        if (CardRelation.TYPE_CONTAINS.equals(s)
                || CardRelation.TYPE_PRECEDES.equals(s)
                || CardRelation.TYPE_DERIVED_FROM.equals(s)) {
            return s;
        }
        return CardRelation.TYPE_RELATED_TO;
    }

    private static float normalizeConfidence(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return 0.8f;
        }
        return Math.max(0.0f, Math.min(1.0f, (float) node.asDouble()));
    }

    private static String textAny(JsonNode node, String first, String second) {
        String value = text(node, first);
        return value != null ? value : text(node, second);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String stripCodeFence(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("AI 提取结果为空");
        }
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

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        return s.isEmpty() ? null : s;
    }
}
