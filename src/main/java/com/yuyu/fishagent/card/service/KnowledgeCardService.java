package com.yuyu.fishagent.card.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.card.dto.CardCreateRequest;
import com.yuyu.fishagent.card.dto.CardListItemVO;
import com.yuyu.fishagent.card.dto.CardPageVO;
import com.yuyu.fishagent.card.dto.CardRelationVO;
import com.yuyu.fishagent.card.dto.CardStatsVO;
import com.yuyu.fishagent.card.dto.CardUpdateRequest;
import com.yuyu.fishagent.card.dto.CardVO;
import com.yuyu.fishagent.card.dto.ExtractRelationVO;
import com.yuyu.fishagent.card.entity.CardRelation;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import com.yuyu.fishagent.card.mapper.CardRelationMapper;
import com.yuyu.fishagent.card.mapper.KnowledgeCardMapper;
import com.yuyu.fishagent.common.cache.CacheConstants;
import com.yuyu.fishagent.rag.service.ChunkClusterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 知识卡片应用服务：封装权限校验、MySQL CRUD 和 confirmed 卡片的 ES 同步。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeCardService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORDS = 8;
    private static final int MAX_KEYWORD_LENGTH = 32;
    private static final String GROUP_UNGROUPED = "__UNGROUPED__";

    private final KnowledgeCardMapper knowledgeCardMapper;
    private final CardRelationMapper cardRelationMapper;
    private final KnowledgeCardEsSyncService esSyncService;
    private final KeywordService keywordService;
    private final CardGroupService cardGroupService;
    private final ChunkClusterService chunkClusterService;

    /**
     * 手动创建卡片：阶段 1 直接进入 confirmed，保证创建后即可在页面和 ES 中检索。
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.CARD_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_STATS, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_RELATIONS, allEntries = true)
    })
    @Transactional
    public Long create(CardCreateRequest req) {
        Long userId = requireUserId();
        KnowledgeCard card = new KnowledgeCard();
        card.setUserId(userId);
        applyEditableFields(card, req.title(), req.content(), req.keywords(), req.cardType(), req.groupName());
        card.setSourceType(KnowledgeCard.SOURCE_MANUAL);
        card.setSourceId(null);
        card.setStatus(KnowledgeCard.STATUS_CONFIRMED);
        knowledgeCardMapper.insert(card);
        keywordService.syncKeywordsForCard(card.getId(), userId, card.getKeywords(), "manual");
        cardGroupService.syncGroupForCard(card.getId(), userId, card.getGroupName());
        esSyncService.syncConfirmedQuietly(card);
        return card.getId();
    }

    /**
     * 分页查询当前用户卡片，支持 groupName（过渡期）和 groupId 筛选。
     */
    public CardPageVO list(long page, long size, String status, String keyword, String groupName, Long groupId) {
        Long userId = requireUserId();
        long current = Math.max(1, page);
        long pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        String safeStatus = normalizeStatusFilter(status);
        String safeKeyword = trimToNull(keyword);
        String safeGroup = normalizeGroupFilter(groupName);
        Long safeGroupId = groupId != null && groupId > 0 ? groupId : null;
        long total = knowledgeCardMapper.countListItems(userId, safeStatus, safeKeyword, safeGroup, safeGroupId);
        List<CardListItemVO> records = total == 0
                ? List.of()
                : knowledgeCardMapper.selectListItems(userId, safeStatus, safeKeyword, safeGroup, safeGroupId, pageSize, (current - 1) * pageSize);
        return new CardPageVO(records, total, current, pageSize);
    }

    /**
     * 查询详情：如果卡片不存在或不属于当前用户，按计划返回 403。
     */
    @Cacheable(cacheNames = CacheConstants.CARD_DETAIL, key = CacheConstants.KEY_CURRENT_USER_CARD_ID)
    public CardVO detail(Long id) {
        Long userId = requireUserId();
        KnowledgeCard card = findOwnedCardOrForbidden(userId, id);
        List<CardRelationVO> relations = cardRelationMapper.selectRelationsForCard(userId, card.getId());
        return toVO(card, relations);
    }

    /**
     * 编辑用户可控字段；confirmed 卡片编辑后同步更新 ES。
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.CARD_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_STATS, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_RELATIONS, allEntries = true)
    })
    @Transactional
    public void update(Long id, CardUpdateRequest req) {
        Long userId = requireUserId();
        KnowledgeCard card = findOwnedCardOrForbidden(userId, id);
        applyEditableFields(card, req.title(), req.content(), req.keywords(), req.cardType(), req.groupName());
        knowledgeCardMapper.updateById(card);
        keywordService.syncKeywordsForCard(card.getId(), userId, card.getKeywords(), "manual");
        cardGroupService.syncGroupForCard(card.getId(), userId, card.getGroupName());
        if (KnowledgeCard.STATUS_CONFIRMED.equals(card.getStatus())) {
            esSyncService.syncConfirmedQuietly(card);
        }
    }

    /**
     * 删除卡片及其双向关联；ES 删除失败不影响 MySQL 主流程。
     *
     * <p>操作顺序遵循一致的锁获取路径（子表 → 关联表 → 主表），避免与其他事务死锁。</p>
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.CARD_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_STATS, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_RELATIONS, allEntries = true)
    })
    @Transactional
    public void delete(Long id) {
        Long userId = requireUserId();
        KnowledgeCard card = findOwnedCardOrForbidden(userId, id);
        // 1. 子表优先：先清理 card_keyword + keyword.card_count
        keywordService.removeKeywordsForCard(card.getId());
        // 1b. 清理 group.card_count
        cardGroupService.removeGroupForCard(card.getId());
        // 2. 拆分 OR 为两条 DELETE，避免 InnoDB gap lock 范围过大导致死锁
        cardRelationMapper.delete(Wrappers.<CardRelation>lambdaQuery()
                .eq(CardRelation::getFromCardId, card.getId()));
        cardRelationMapper.delete(Wrappers.<CardRelation>lambdaQuery()
                .eq(CardRelation::getToCardId, card.getId()));
        // 3. 最后删主表
        knowledgeCardMapper.deleteById(card.getId());
        esSyncService.deleteQuietly(card.getId());
    }

    /**
     * 批量确认 pending 卡片：状态入库后逐张写 ES，ES 失败不回滚 MySQL 状态。
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.CARD_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_STATS, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_RELATIONS, allEntries = true)
    })
    @Transactional
    public void batchConfirm(List<Long> ids) {
        Long userId = requireUserId();
        List<KnowledgeCard> cards = ownedCards(userId, ids);
        for (KnowledgeCard card : cards) {
            card.setStatus(KnowledgeCard.STATUS_CONFIRMED);
            knowledgeCardMapper.updateById(card);
            esSyncService.syncConfirmedQuietly(card);
        }
    }

    /**
     * 批量拒绝卡片：拒绝态不参与检索，确认过的卡片也会从 ES 移除。
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.CARD_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_STATS, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_RELATIONS, allEntries = true)
    })
    @Transactional
    public void batchReject(List<Long> ids) {
        Long userId = requireUserId();
        List<KnowledgeCard> cards = ownedCards(userId, ids);
        for (KnowledgeCard card : cards) {
            card.setStatus(KnowledgeCard.STATUS_REJECTED);
            knowledgeCardMapper.updateById(card);
            esSyncService.deleteQuietly(card.getId());
        }
    }

    /**
     * 合并疑似重复卡片：保留 keep 的标题正文，迁移 discard 的关键词和所有关联后删除 discard。
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.CARD_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_STATS, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_RELATIONS, allEntries = true)
    })
    @Transactional
    public void merge(Long keepId, Long discardId) {
        Long userId = requireUserId();
        if (keepId == null || discardId == null || keepId.equals(discardId)) {
            throw new IllegalArgumentException("keepId 和 discardId 不合法");
        }
        KnowledgeCard keep = findOwnedCardOrForbidden(userId, keepId);
        KnowledgeCard discard = findOwnedCardOrForbidden(userId, discardId);
        keep.setKeywords(mergeKeywords(keep.getKeywords(), discard.getKeywords()));
        knowledgeCardMapper.updateById(keep);
        keywordService.syncKeywordsForCard(keepId, userId, keep.getKeywords(), "manual");

        List<CardRelation> relations = cardRelationMapper.selectList(Wrappers.<CardRelation>lambdaQuery()
                .eq(CardRelation::getFromCardId, discardId)
                .or()
                .eq(CardRelation::getToCardId, discardId));
        for (CardRelation rel : relations) {
            Long from = rel.getFromCardId().equals(discardId) ? keepId : rel.getFromCardId();
            Long to = rel.getToCardId().equals(discardId) ? keepId : rel.getToCardId();
            if (from.equals(to)) {
                continue;
            }
            createRelationQuietly(from, to, normalizeRelationType(rel.getRelationType()), rel.getConfidence());
        }

        // 子表优先：先清理关键词和分组，再拆分删除关联
        keywordService.removeKeywordsForCard(discardId);
        cardGroupService.removeGroupForCard(discardId);
        cardRelationMapper.delete(Wrappers.<CardRelation>lambdaQuery()
                .eq(CardRelation::getFromCardId, discardId));
        cardRelationMapper.delete(Wrappers.<CardRelation>lambdaQuery()
                .eq(CardRelation::getToCardId, discardId));
        knowledgeCardMapper.deleteById(discardId);
        esSyncService.deleteQuietly(discardId);
        if (KnowledgeCard.STATUS_CONFIRMED.equals(keep.getStatus())) {
            esSyncService.syncConfirmedQuietly(keep);
        }
    }

    /**
     * 手动新增关联：两端卡片都必须属于当前用户。
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.CARD_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_STATS, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_RELATIONS, allEntries = true)
    })
    @Transactional
    public Long addRelation(Long fromCardId, Long toCardId, String relationType) {
        Long userId = requireUserId();
        findOwnedCardOrForbidden(userId, fromCardId);
        findOwnedCardOrForbidden(userId, toCardId);
        if (fromCardId.equals(toCardId)) {
            throw new IllegalArgumentException("不能关联同一张卡片");
        }
        CardRelation relation = new CardRelation();
        relation.setFromCardId(fromCardId);
        relation.setToCardId(toCardId);
        relation.setRelationType(normalizeRelationType(relationType));
        relation.setConfidence(1.0f);
        try {
            cardRelationMapper.insert(relation);
        } catch (DuplicateKeyException ignored) {
            CardRelation existing = cardRelationMapper.selectOne(Wrappers.<CardRelation>lambdaQuery()
                    .eq(CardRelation::getFromCardId, fromCardId)
                    .eq(CardRelation::getToCardId, toCardId)
                    .eq(CardRelation::getRelationType, relation.getRelationType()));
            return existing == null ? relation.getId() : existing.getId();
        }
        return relation.getId();
    }

    /**
     * 删除关联前通过关联两端卡片做归属校验，避免越权删除他人的边。
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.CARD_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_STATS, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.CARD_RELATIONS, allEntries = true)
    })
    @Transactional
    public void deleteRelation(Long relationId) {
        Long userId = requireUserId();
        CardRelation relation = cardRelationMapper.selectById(relationId);
        if (relation == null) {
            throw new ResponseStatusException(NOT_FOUND, "关联不存在");
        }
        findOwnedCardOrForbidden(userId, relation.getFromCardId());
        findOwnedCardOrForbidden(userId, relation.getToCardId());
        cardRelationMapper.deleteById(relationId);
    }

    public List<CardRelationVO> relations(Long cardId) {
        Long userId = requireUserId();
        findOwnedCardOrForbidden(userId, cardId);
        return cardRelationMapper.selectRelationsForCard(userId, cardId);
    }

    /**
     * 当前用户全部关联边，供图谱视图一次性组装边集合，避免前端逐卡 N+1 请求。
     */
    @Cacheable(cacheNames = CacheConstants.CARD_RELATIONS, key = CacheConstants.KEY_CURRENT_USER)
    public List<ExtractRelationVO> allRelations() {
        Long userId = requireUserId();
        return cardRelationMapper.selectAllRelationsForUser(userId);
    }

    /**
     * 当前用户的卡片统计，供顶部概览和分组 Tab 复用。
     */
    @Cacheable(cacheNames = CacheConstants.CARD_STATS, key = CacheConstants.KEY_CURRENT_USER)
    public CardStatsVO stats() {
        Long userId = requireUserId();
        long total = knowledgeCardMapper.selectCount(Wrappers.<KnowledgeCard>lambdaQuery()
                .eq(KnowledgeCard::getUserId, userId));
        long confirmed = knowledgeCardMapper.selectCount(Wrappers.<KnowledgeCard>lambdaQuery()
                .eq(KnowledgeCard::getUserId, userId)
                .eq(KnowledgeCard::getStatus, KnowledgeCard.STATUS_CONFIRMED));
        long pending = knowledgeCardMapper.selectCount(Wrappers.<KnowledgeCard>lambdaQuery()
                .eq(KnowledgeCard::getUserId, userId)
                .eq(KnowledgeCard::getStatus, KnowledgeCard.STATUS_PENDING));
        long weekNew = knowledgeCardMapper.selectCount(Wrappers.<KnowledgeCard>lambdaQuery()
                .eq(KnowledgeCard::getUserId, userId)
                .ge(KnowledgeCard::getCreatedAt, LocalDateTime.now().minusDays(7)));
        long relationCount = cardRelationMapper.countForUser(userId);
        return new CardStatsVO(total, confirmed, pending, relationCount, weekNew, cardGroupService.getUserGroupTree(userId));
    }

    private List<KnowledgeCard> ownedCards(Long userId, List<Long> ids) {
        List<Long> safeIds = ids == null ? List.of() : ids.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .limit(200)
                .toList();
        if (safeIds.isEmpty()) {
            return List.of();
        }
        List<KnowledgeCard> cards = knowledgeCardMapper.selectList(Wrappers.<KnowledgeCard>lambdaQuery()
                .eq(KnowledgeCard::getUserId, userId)
                .in(KnowledgeCard::getId, safeIds));
        Map<Long, KnowledgeCard> byId = cards.stream().collect(Collectors.toMap(KnowledgeCard::getId, Function.identity()));
        for (Long id : safeIds) {
            if (!byId.containsKey(id)) {
                throw new ResponseStatusException(FORBIDDEN, "无权操作部分卡片");
            }
        }
        return safeIds.stream().map(byId::get).toList();
    }

    private KnowledgeCard findOwnedCardOrForbidden(Long userId, Long id) {
        if (id == null) {
            throw new IllegalArgumentException("卡片 ID 不能为空");
        }
        KnowledgeCard card = knowledgeCardMapper.selectById(id);
        if (card == null) {
            throw new ResponseStatusException(NOT_FOUND, "卡片不存在");
        }
        if (!userId.equals(card.getUserId())) {
            throw new ResponseStatusException(FORBIDDEN, "无权访问该卡片");
        }
        return card;
    }

    private CardVO toVO(KnowledgeCard card, List<CardRelationVO> relations) {
        // 构建分组面包屑路径
        String groupPath = null;
        if (card.getGroupId() != null) {
            groupPath = cardGroupService.getGroupPath(card.getGroupId()).stream()
                    .map(g -> g.getName())
                    .collect(java.util.stream.Collectors.joining(" > "));
        } else if (card.getGroupName() != null && !card.getGroupName().isBlank()) {
            groupPath = card.getGroupName();
        }
        return new CardVO(
                card.getId(),
                card.getTitle(),
                card.getContent(),
                safeKeywords(card.getKeywords()),
                card.getCardType(),
                card.getSourceType(),
                card.getSourceId(),
                card.getStatus(),
                card.getGroupName(),
                card.getGroupId(),
                groupPath,
                relations == null ? List.of() : relations,
                chunkClusterService.findRelatedChunksForCard(card),
                card.getCreatedAt(),
                card.getUpdatedAt()
        );
    }

    private static void applyEditableFields(KnowledgeCard card, String title, String content,
                                            List<String> keywords, String cardType, String groupName) {
        String safeTitle = trimToNull(title);
        String safeContent = trimToNull(content);
        if (safeTitle == null) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (safeTitle.length() > 200) {
            throw new IllegalArgumentException("标题不能超过 200 字");
        }
        if (safeContent == null) {
            throw new IllegalArgumentException("内容不能为空");
        }
        card.setTitle(safeTitle);
        card.setContent(safeContent);
        card.setKeywords(normalizeKeywords(keywords));
        card.setCardType(normalizeCardType(cardType));
        card.setGroupName(trimToNull(groupName));
    }

    private static List<String> normalizeKeywords(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String item : raw) {
            String s = trimToNull(item);
            if (s == null) {
                continue;
            }
            unique.add(s.length() > MAX_KEYWORD_LENGTH ? s.substring(0, MAX_KEYWORD_LENGTH) : s);
            if (unique.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    private static List<String> mergeKeywords(List<String> a, List<String> b) {
        List<String> merged = new ArrayList<>();
        merged.addAll(safeKeywords(a));
        merged.addAll(safeKeywords(b));
        return normalizeKeywords(merged);
    }

    private static String normalizeCardType(String raw) {
        String s = trimToNull(raw);
        if (s == null) {
            return KnowledgeCard.TYPE_CONCEPT;
        }
        if (KnowledgeCard.TYPE_CONCEPT.equals(s) || KnowledgeCard.TYPE_TOPIC.equals(s)) {
            return s;
        }
        throw new IllegalArgumentException("cardType 只能是 concept 或 topic");
    }

    private static String normalizeStatusFilter(String raw) {
        String s = trimToNull(raw);
        if (s == null || "all".equalsIgnoreCase(s)) {
            return null;
        }
        if (KnowledgeCard.STATUS_CONFIRMED.equals(s)
                || KnowledgeCard.STATUS_PENDING.equals(s)
                || KnowledgeCard.STATUS_REJECTED.equals(s)) {
            return s;
        }
        throw new IllegalArgumentException("status 参数不合法");
    }

    private static String normalizeGroupFilter(String raw) {
        String s = trimToNull(raw);
        if (s == null || "all".equalsIgnoreCase(s)) {
            return null;
        }
        if ("未分组".equals(s)) {
            return GROUP_UNGROUPED;
        }
        return s;
    }

    public void createRelationQuietly(Long fromCardId, Long toCardId, String relationType, Float confidence) {
        if (fromCardId == null || toCardId == null || fromCardId.equals(toCardId)) {
            return;
        }
        try {
            CardRelation relation = new CardRelation();
            relation.setFromCardId(fromCardId);
            relation.setToCardId(toCardId);
            relation.setRelationType(normalizeRelationType(relationType));
            relation.setConfidence(confidence == null ? 1.0f : Math.max(0.0f, Math.min(1.0f, confidence)));
            cardRelationMapper.insert(relation);
        } catch (DuplicateKeyException ignored) {
            // 唯一索引负责去重；重复边对用户无感，直接跳过。
        } catch (Exception e) {
            log.warn("[KnowledgeCard] 创建关联失败 from={} to={}: {}", fromCardId, toCardId, e.getMessage());
        }
    }

    public static String normalizeRelationType(String raw) {
        String s = trimToNull(raw);
        if (s == null) {
            return CardRelation.TYPE_RELATED_TO;
        }
        if (CardRelation.TYPE_RELATED_TO.equals(s)
                || CardRelation.TYPE_CONTAINS.equals(s)
                || CardRelation.TYPE_PRECEDES.equals(s)
                || CardRelation.TYPE_DERIVED_FROM.equals(s)) {
            return s;
        }
        throw new IllegalArgumentException("relationType 不合法");
    }

    private Long requireUserId() {
        Long userId = UserContextHolder.currentUserIdOrNull();
        if (userId == null) {
            throw new IllegalStateException("未登录");
        }
        return userId;
    }

    private static List<String> safeKeywords(List<String> keywords) {
        return keywords == null ? List.of() : keywords;
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        return s.isEmpty() ? null : s;
    }
}
