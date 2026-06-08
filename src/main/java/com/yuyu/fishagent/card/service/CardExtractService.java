package com.yuyu.fishagent.card.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.fishagent.card.dto.CardRelationVO;
import com.yuyu.fishagent.card.dto.CardVO;
import com.yuyu.fishagent.card.dto.GroupTreeNode;
import com.yuyu.fishagent.card.dto.ExtractRelationVO;
import com.yuyu.fishagent.card.dto.ExtractResult;
import com.yuyu.fishagent.card.entity.CardRelation;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import com.yuyu.fishagent.card.entity.CardKeyword;
import com.yuyu.fishagent.card.entity.Keyword;
import com.yuyu.fishagent.card.extract.CardExtractPromptBuilder;
import com.yuyu.fishagent.card.extract.CardExtractResponseParser;
import com.yuyu.fishagent.card.extract.CardExtractionDraft;
import com.yuyu.fishagent.card.extract.ExtractedCardDraft;
import com.yuyu.fishagent.card.extract.ExtractedRelationDraft;
import com.yuyu.fishagent.card.mapper.CardRelationMapper;
import com.yuyu.fishagent.card.mapper.CardKeywordMapper;
import com.yuyu.fishagent.card.mapper.KnowledgeCardMapper;
import com.yuyu.fishagent.chat.ChatMetadataService;
import com.yuyu.fishagent.chat.history.ChatMemoryStore;
import com.yuyu.fishagent.common.cache.CacheConstants;
import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 知识卡片提取管线：对话加载、长对话摘要、LLM 提取、入库与关联发现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardExtractService {

    private static final int DIRECT_TOKEN_THRESHOLD = 4000;
    private static final int RECENT_MESSAGE_LIMIT = 20;
    private static final float EXTERNAL_RELATION_THRESHOLD = 0.75f;

    private final ChatMemoryStore chatMemoryStore;
    private final ChatMetadataService chatMetadataService;
    private final KnowledgeCardMapper knowledgeCardMapper;
    private final CardRelationMapper cardRelationMapper;
    private final CardKeywordMapper cardKeywordMapper;
    private final KnowledgeCardEsSyncService esSyncService;
    private final KeywordService keywordService;
    private final CardGroupService cardGroupService;
    private final CardExtractPromptBuilder promptBuilder;
    private final CardExtractResponseParser responseParser;

    /** 使用记忆链路模型做提取，减少对主对话模型参数的耦合。 */
    @Qualifier("memoryChatModel")
    private final ChatModel chatModel;

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.CARD_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_STATS, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_RELATIONS, allEntries = true)
    })
    @Transactional
    public ExtractResult extractFromSession(String sessionId, Long userId) {
        if (userId == null) {
            throw new IllegalStateException("未登录");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        chatMetadataService.assertOwnedByCurrentUser(sessionId);
        List<ChatMessageDTO> messages = loadDialogue(sessionId);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("当前会话没有可提取的对话内容");
        }

        String preparedConversation = prepareConversation(messages);
        List<String> existingKeywords = keywordService.getUserKeywords(userId).stream()
                .map(Keyword::getName)
                .toList();
        List<GroupTreeNode> existingGroupTree = cardGroupService.getUserGroupTree(userId);
        String raw = chatModel.call(promptBuilder.buildExtractPrompt(preparedConversation, existingKeywords, existingGroupTree))
                .getResult().getOutput().getText();
        CardExtractionDraft draft = responseParser.parse(raw);
        if (draft.cards().isEmpty()) {
            return new ExtractResult(0, List.of(), List.of(), List.of());
        }

        Map<String, KnowledgeCard> titleToCard = insertPendingCards(draft.cards(), userId, sessionId);
        List<ExtractRelationVO> relations = new ArrayList<>();
        relations.addAll(createInternalRelations(draft.relations(), titleToCard));
        relations.addAll(createExternalRelations(userId, titleToCard.values().stream().toList()));

        List<CardVO> cards = titleToCard.values().stream()
                .map(card -> toCardVO(userId, card))
                .toList();
        List<Long> cardIds = titleToCard.values().stream().map(KnowledgeCard::getId).toList();
        return new ExtractResult(cards.size(), cardIds, cards, relations);
    }

    private List<ChatMessageDTO> loadDialogue(String sessionId) {
        return chatMemoryStore.load(sessionId).stream()
                .filter(m -> m.getRole() != null)
                .filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole()))
                .filter(m -> m.getContent() != null && !m.getContent().isBlank())
                .toList();
    }

    private String prepareConversation(List<ChatMessageDTO> messages) {
        String full = formatMessages(messages);
        if (estimateTokens(full) <= DIRECT_TOKEN_THRESHOLD) {
            return full;
        }

        int mid = Math.max(1, messages.size() / 2);
        String prefix = formatMessages(messages.subList(0, mid));
        String summary;
        try {
            summary = chatModel.call(promptBuilder.buildSummaryPrompt(prefix)).getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[CardExtract] 长对话摘要失败，降级为最近消息提取: {}", e.getMessage());
            summary = "";
        }
        List<ChatMessageDTO> recent = messages.subList(Math.max(0, messages.size() - RECENT_MESSAGE_LIMIT), messages.size());
        return """
                [对话摘要]
                %s

                [近期对话原文]
                %s
                """.formatted(nullToBlank(summary).trim(), formatMessages(recent));
    }

    private Map<String, KnowledgeCard> insertPendingCards(List<ExtractedCardDraft> drafts, Long userId, String sessionId) {
        Map<String, KnowledgeCard> titleToCard = new LinkedHashMap<>();
        for (ExtractedCardDraft draft : drafts) {
            KnowledgeCard card = new KnowledgeCard();
            card.setUserId(userId);
            card.setTitle(draft.title());
            card.setContent(draft.content());
            card.setKeywords(draft.keywords() == null ? List.of() : draft.keywords());
            card.setCardType(draft.cardType());
            card.setGroupName(draft.groupName());
            card.setSourceType(KnowledgeCard.SOURCE_CHAT);
            card.setSourceId(sessionId);
            card.setStatus(KnowledgeCard.STATUS_PENDING);
            knowledgeCardMapper.insert(card);
            keywordService.syncKeywordsForCard(card.getId(), userId, card.getKeywords(), "ai");
            cardGroupService.syncGroupForCard(card.getId(), userId, card.getGroupName());
            titleToCard.put(card.getTitle(), card);
        }
        return titleToCard;
    }

    private List<ExtractRelationVO> createInternalRelations(List<ExtractedRelationDraft> drafts,
                                                           Map<String, KnowledgeCard> titleToCard) {
        List<ExtractRelationVO> out = new ArrayList<>();
        for (ExtractedRelationDraft draft : drafts) {
            KnowledgeCard from = titleToCard.get(draft.fromTitle());
            KnowledgeCard to = titleToCard.get(draft.toTitle());
            if (from == null || to == null) {
                continue;
            }
            CardRelation relation = insertRelation(from.getId(), to.getId(), draft.relationType(), draft.confidence());
            if (relation != null) {
                out.add(toExtractRelation(relation));
            }
        }
        return out;
    }

    private List<ExtractRelationVO> createExternalRelations(Long userId, List<KnowledgeCard> cards) {
        List<ExtractRelationVO> out = new ArrayList<>();
        for (KnowledgeCard card : cards) {
            for (KnowledgeCardEsSyncService.CardVectorHit hit : esSyncService.findSimilarConfirmed(userId, card, 5)) {
                if (hit.score() < EXTERNAL_RELATION_THRESHOLD) {
                    continue;
                }
                CardRelation relation = insertRelation(card.getId(), hit.cardId(), CardRelation.TYPE_RELATED_TO, hit.score());
                if (relation != null) {
                    out.add(toExtractRelation(relation));
                }
            }
            out.addAll(createKeywordGroupRelations(userId, card));
        }
        return out;
    }

    private List<ExtractRelationVO> createKeywordGroupRelations(Long userId, KnowledgeCard card) {
        if (card.getGroupId() == null && (card.getGroupName() == null || card.getGroupName().isBlank())) {
            return List.of();
        }
        List<Long> keywordIds = cardKeywordMapper.selectList(Wrappers.<CardKeyword>lambdaQuery()
                        .eq(CardKeyword::getCardId, card.getId()))
                .stream()
                .map(CardKeyword::getKeywordId)
                .toList();
        if (keywordIds.isEmpty()) {
            return List.of();
        }
        Set<Long> candidateIds = new HashSet<>(cardKeywordMapper.selectCardIdsByKeywordIds(keywordIds));
        candidateIds.remove(card.getId());
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        // 优先用 group_id 匹配，过渡期兼容 group_name
        var query = Wrappers.<KnowledgeCard>lambdaQuery()
                .eq(KnowledgeCard::getUserId, userId)
                .eq(KnowledgeCard::getStatus, KnowledgeCard.STATUS_CONFIRMED)
                .in(KnowledgeCard::getId, candidateIds);
        if (card.getGroupId() != null) {
            query.eq(KnowledgeCard::getGroupId, card.getGroupId());
        } else {
            query.eq(KnowledgeCard::getGroupName, card.getGroupName());
        }
        List<KnowledgeCard> candidates = knowledgeCardMapper.selectList(query);
        List<ExtractRelationVO> out = new ArrayList<>();
        for (KnowledgeCard candidate : candidates) {
            CardRelation relation = insertRelation(card.getId(), candidate.getId(), CardRelation.TYPE_RELATED_TO, 0.55f);
            if (relation != null) {
                out.add(toExtractRelation(relation));
            }
        }
        return out;
    }

    private CardRelation insertRelation(Long fromCardId, Long toCardId, String relationType, Float confidence) {
        if (fromCardId == null || toCardId == null || fromCardId.equals(toCardId)) {
            return null;
        }
        try {
            CardRelation relation = new CardRelation();
            relation.setFromCardId(fromCardId);
            relation.setToCardId(toCardId);
            relation.setRelationType(KnowledgeCardService.normalizeRelationType(relationType));
            relation.setConfidence(confidence == null ? 0.8f : Math.max(0.0f, Math.min(1.0f, confidence)));
            cardRelationMapper.insert(relation);
            return relation;
        } catch (Exception e) {
            log.debug("[CardExtract] 关联写入跳过 from={} to={} type={}: {}",
                    fromCardId, toCardId, relationType, e.getMessage());
            return null;
        }
    }

    private CardVO toCardVO(Long userId, KnowledgeCard card) {
        List<CardRelationVO> relations = cardRelationMapper.selectRelationsForCard(userId, card.getId());
        String groupPath = card.getGroupName();
        if (card.getGroupId() != null) {
            groupPath = cardGroupService.getGroupPath(card.getGroupId()).stream()
                    .map(g -> g.getName())
                    .collect(java.util.stream.Collectors.joining(" > "));
        }
        return new CardVO(
                card.getId(),
                card.getTitle(),
                card.getContent(),
                card.getKeywords() == null ? List.of() : card.getKeywords(),
                card.getCardType(),
                card.getSourceType(),
                card.getSourceId(),
                card.getStatus(),
                card.getGroupName(),
                card.getGroupId(),
                groupPath,
                relations,
                List.of(),
                card.getCreatedAt(),
                card.getUpdatedAt()
        );
    }

    private static ExtractRelationVO toExtractRelation(CardRelation relation) {
        return new ExtractRelationVO(
                relation.getId(),
                relation.getFromCardId(),
                relation.getToCardId(),
                relation.getRelationType(),
                relation.getConfidence()
        );
    }

    private static String formatMessages(List<ChatMessageDTO> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessageDTO m : messages) {
            String role = "assistant".equals(m.getRole()) ? "助手" : "用户";
            sb.append(role).append("：").append(m.getContent().trim()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Math.ceil(text.trim().length() * 1.5);
    }

    private static String nullToBlank(String text) {
        return text == null ? "" : text;
    }
}
