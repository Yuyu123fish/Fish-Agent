package com.yuyu.fishagent.card.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.fishagent.card.entity.CardKeyword;
import com.yuyu.fishagent.card.entity.Keyword;
import com.yuyu.fishagent.card.entity.KeywordRelation;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import com.yuyu.fishagent.card.mapper.CardKeywordMapper;
import com.yuyu.fishagent.card.mapper.KeywordMapper;
import com.yuyu.fishagent.card.mapper.KeywordRelationMapper;
import com.yuyu.fishagent.card.mapper.KnowledgeCardMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关键词实体服务：维护 keywords JSON 与 keyword/card_keyword 归一化索引之间的一致性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordService {

    private static final int MAX_KEYWORD_LENGTH = 50;

    private final KeywordMapper keywordMapper;
    private final CardKeywordMapper cardKeywordMapper;
    private final KeywordRelationMapper keywordRelationMapper;
    private final KnowledgeCardMapper knowledgeCardMapper;

    /**
     * 为卡片重建关键词索引。采用先删后建，保证编辑关键词后旧关联被清理。
     */
    @Transactional
    public List<Keyword> syncKeywordsForCard(Long cardId, Long userId, List<String> keywordNames, String source) {
        if (cardId == null || userId == null) {
            return List.of();
        }
        removeKeywordsForCard(cardId);

        Map<String, String> normalizedToName = normalizeNames(keywordNames);
        if (normalizedToName.isEmpty()) {
            return List.of();
        }

        List<Keyword> keywords = new ArrayList<>();
        for (Map.Entry<String, String> entry : normalizedToName.entrySet()) {
            Keyword keyword = findOrCreate(userId, entry.getValue(), entry.getKey());
            keywords.add(keyword);

            CardKeyword link = new CardKeyword();
            link.setCardId(cardId);
            link.setKeywordId(keyword.getId());
            link.setSource(normalizeSource(source));
            try {
                cardKeywordMapper.insert(link);
                keywordMapper.updateCardCount(keyword.getId(), 1);
            } catch (DuplicateKeyException ignored) {
                // 幂等保护：唯一索引已经存在时不重复计数。
            }
        }
        return keywords;
    }

    /**
     * 删除卡片关键词关联，并回退关键词 card_count。
     */
    @Transactional
    public void removeKeywordsForCard(Long cardId) {
        if (cardId == null) {
            return;
        }
        List<CardKeyword> oldLinks = cardKeywordMapper.selectList(Wrappers.<CardKeyword>lambdaQuery()
                .eq(CardKeyword::getCardId, cardId));
        if (oldLinks.isEmpty()) {
            return;
        }
        cardKeywordMapper.deleteByCardId(cardId);
        for (CardKeyword link : oldLinks) {
            keywordMapper.updateCardCount(link.getKeywordId(), -1);
        }
    }

    public List<Keyword> getUserKeywords(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return keywordMapper.selectByUserId(userId);
    }

    /**
     * 获取关键词被哪些卡片分组使用，供后续 prompt 或分析扩展。
     */
    public Map<String, Set<String>> getKeywordGroupMap(Long userId) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (userId == null) {
            return out;
        }
        List<KnowledgeCard> cards = knowledgeCardMapper.selectList(Wrappers.<KnowledgeCard>lambdaQuery()
                .eq(KnowledgeCard::getUserId, userId));
        for (KnowledgeCard card : cards) {
            List<Keyword> keywords = keywordMapper.selectKeywordsByCardId(card.getId());
            for (Keyword keyword : keywords) {
                out.computeIfAbsent(keyword.getName(), k -> new LinkedHashSet<>());
                if (card.getGroupName() != null && !card.getGroupName().isBlank()) {
                    out.get(keyword.getName()).add(card.getGroupName());
                }
            }
        }
        return out;
    }

    @Transactional
    public void addKeywordRelation(Long userId, Long fromId, Long toId, String type, Float confidence) {
        if (userId == null || fromId == null || toId == null || fromId.equals(toId)) {
            return;
        }
        KeywordRelation relation = new KeywordRelation();
        relation.setUserId(userId);
        relation.setFromKeywordId(fromId);
        relation.setToKeywordId(toId);
        relation.setRelationType(normalizeRelationType(type));
        relation.setConfidence(confidence == null ? 1.0f : Math.max(0.0f, Math.min(1.0f, confidence)));
        try {
            keywordRelationMapper.insert(relation);
        } catch (DuplicateKeyException ignored) {
            // 关系唯一索引保证幂等。
        }
    }

    /**
     * 将历史 cards.keywords JSON 迁移到 keyword/card_keyword。重复执行不会重复计数。
     */
    @Transactional
    public int migrateExistingKeywords(Long userId) {
        if (userId == null) {
            return 0;
        }
        List<KnowledgeCard> cards = knowledgeCardMapper.selectList(Wrappers.<KnowledgeCard>lambdaQuery()
                .eq(KnowledgeCard::getUserId, userId));
        int migrated = 0;
        for (KnowledgeCard card : cards) {
            if (card.getKeywords() == null || card.getKeywords().isEmpty()) {
                continue;
            }
            long existing = cardKeywordMapper.selectCount(Wrappers.<CardKeyword>lambdaQuery()
                    .eq(CardKeyword::getCardId, card.getId()));
            if (existing > 0) {
                continue;
            }
            String source = KnowledgeCard.SOURCE_MANUAL.equals(card.getSourceType())
                    ? CardKeyword.SOURCE_MANUAL : CardKeyword.SOURCE_AI;
            syncKeywordsForCard(card.getId(), userId, card.getKeywords(), source);
            migrated++;
        }
        return migrated;
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > MAX_KEYWORD_LENGTH ? s.substring(0, MAX_KEYWORD_LENGTH) : s;
    }

    private Keyword findOrCreate(Long userId, String name, String normalizedName) {
        Keyword existing = keywordMapper.selectByUserIdAndNormalizedName(userId, normalizedName);
        if (existing != null) {
            return existing;
        }
        Keyword keyword = new Keyword();
        keyword.setUserId(userId);
        keyword.setName(name);
        keyword.setNormalizedName(normalizedName);
        keyword.setCardCount(0);
        try {
            keywordMapper.insert(keyword);
            return keyword;
        } catch (DuplicateKeyException ignored) {
            return keywordMapper.selectByUserIdAndNormalizedName(userId, normalizedName);
        }
    }

    private static Map<String, String> normalizeNames(List<String> rawNames) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawNames == null) {
            return out;
        }
        for (String raw : rawNames) {
            String normalized = normalize(raw);
            if (normalized == null) {
                continue;
            }
            String display = raw.trim();
            if (display.length() > MAX_KEYWORD_LENGTH) {
                display = display.substring(0, MAX_KEYWORD_LENGTH);
            }
            out.putIfAbsent(normalized, display);
        }
        return out;
    }

    private static String normalizeSource(String source) {
        return CardKeyword.SOURCE_MANUAL.equals(source) ? CardKeyword.SOURCE_MANUAL : CardKeyword.SOURCE_AI;
    }

    private static String normalizeRelationType(String type) {
        String s = type == null ? "" : type.trim();
        if (Keyword.TYPE_SYNONYM.equals(s)
                || Keyword.TYPE_BROADER.equals(s)
                || Keyword.TYPE_NARROWER.equals(s)
                || Keyword.TYPE_RELATED.equals(s)) {
            return s;
        }
        return Keyword.TYPE_RELATED;
    }
}
