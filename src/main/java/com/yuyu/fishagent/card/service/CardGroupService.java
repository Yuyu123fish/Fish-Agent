package com.yuyu.fishagent.card.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.fishagent.card.dto.GroupTreeNode;
import com.yuyu.fishagent.card.entity.CardGroup;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import com.yuyu.fishagent.card.mapper.CardGroupMapper;
import com.yuyu.fishagent.card.mapper.KnowledgeCardMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分组实体服务：维护 knowledge_card.group_name 与 card_group 归一化索引之间的一致性。
 *
 * <p>设计上与 {@link KeywordService} 同构：findOrCreate 归一化幂等创建，
 * sync/remove 维护 card_count，getUserGroupTree 组装树形结构。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardGroupService {

    private static final int MAX_NAME_LENGTH = 100;

    private final CardGroupMapper cardGroupMapper;
    private final KnowledgeCardMapper knowledgeCardMapper;

    // ─── 归一化 + 幂等创建 ───

    /**
     * 归一化名称后查找或创建分组。parentId 可为 null（表示顶层分组）。
     */
    @Transactional
    public CardGroup findOrCreate(Long userId, String name, Long parentId) {
        String normalizedName = normalize(name);
        if (normalizedName == null || userId == null) {
            return null;
        }
        CardGroup existing = cardGroupMapper.selectByUserIdAndNormalizedName(userId, normalizedName);
        if (existing != null) {
            return existing;
        }
        CardGroup group = new CardGroup();
        group.setUserId(userId);
        group.setName(name.trim());
        group.setNormalizedName(normalizedName);
        group.setParentId(parentId);
        group.setCardCount(0);
        try {
            cardGroupMapper.insert(group);
            return group;
        } catch (DuplicateKeyException ignored) {
            return cardGroupMapper.selectByUserIdAndNormalizedName(userId, normalizedName);
        }
    }

    // ─── 卡片同步 ───

    /**
     * 卡片创建/编辑时同步分组：找或建 group → 写入 card.groupId + group_name + card_count++。
     * 如果卡片当前已有 groupId，先回退旧 count 再同步新 group。
     *
     * @return 关联的 CardGroup，groupName 为空时返回 null
     */
    @Transactional
    public CardGroup syncGroupForCard(Long cardId, Long userId, String groupName) {
        if (cardId == null || userId == null) {
            return null;
        }
        // 先回退旧分组计数
        removeGroupForCard(cardId);

        String trimmed = groupName == null ? "" : groupName.trim();
        if (trimmed.isEmpty()) {
            // 无分组：清空 groupId 和 groupName
            KnowledgeCard card = knowledgeCardMapper.selectById(cardId);
            if (card != null && card.getGroupId() != null) {
                card.setGroupId(null);
                card.setGroupName(null);
                knowledgeCardMapper.updateById(card);
            }
            return null;
        }

        // 解析路径格式的 groupName，如 "算法基础/动态规划"
        // 暂时先支持扁平名（parentId=null），后续可扩展路径解析
        CardGroup group = findOrCreate(userId, trimmed, null);

        // 写入卡片的 groupId + groupName（双写过渡期）
        KnowledgeCard card = knowledgeCardMapper.selectById(cardId);
        if (card != null && group != null) {
            card.setGroupId(group.getId());
            card.setGroupName(group.getName());
            knowledgeCardMapper.updateById(card);
            cardGroupMapper.updateCardCount(group.getId(), 1);
        }
        return group;
    }

    /**
     * 卡片删除时清理：card_count--，不删除分组本身。
     */
    @Transactional
    public void removeGroupForCard(Long cardId) {
        if (cardId == null) {
            return;
        }
        KnowledgeCard card = knowledgeCardMapper.selectById(cardId);
        if (card == null || card.getGroupId() == null) {
            return;
        }
        cardGroupMapper.updateCardCount(card.getGroupId(), -1);
    }

    // ─── 树形查询 ───

    /**
     * 返回用户分组树，供前端渲染树形 Tab 和 cascader。
     */
    public List<GroupTreeNode> getUserGroupTree(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<CardGroup> allGroups = cardGroupMapper.selectByUserId(userId);
        return buildTree(allGroups);
    }

    /**
     * 返回某分组到根的路径（面包屑）。
     */
    public List<CardGroup> getGroupPath(Long groupId) {
        List<CardGroup> path = new ArrayList<>();
        if (groupId == null) {
            return path;
        }
        CardGroup current = cardGroupMapper.selectById(groupId);
        // 安全深度限制，防止脏数据死循环
        int maxDepth = 20;
        while (current != null && maxDepth-- > 0) {
            path.addFirst(current);
            if (current.getParentId() == null) {
                break;
            }
            current = cardGroupMapper.selectById(current.getParentId());
        }
        return path;
    }

    // ─── 数据迁移 ───

    /**
     * 将现有 group_name 迁移到 card_group + 回填 group_id。重复执行不会重复计数。
     */
    @Transactional
    public int migrateExistingGroups(Long userId) {
        if (userId == null) {
            return 0;
        }
        // 查出该用户所有非空的 group_name
        List<KnowledgeCard> cards = knowledgeCardMapper.selectList(Wrappers.<KnowledgeCard>lambdaQuery()
                .eq(KnowledgeCard::getUserId, userId)
                .isNotNull(KnowledgeCard::getGroupName)
                .ne(KnowledgeCard::getGroupName, ""));
        if (cards.isEmpty()) {
            return 0;
        }

        // 按 group_name 分组
        Map<String, List<KnowledgeCard>> byGroup = new LinkedHashMap<>();
        for (KnowledgeCard card : cards) {
            byGroup.computeIfAbsent(card.getGroupName(), k -> new ArrayList<>()).add(card);
        }

        int migrated = 0;
        for (Map.Entry<String, List<KnowledgeCard>> entry : byGroup.entrySet()) {
            String groupName = entry.getKey();
            CardGroup group = findOrCreate(userId, groupName, null);
            if (group == null) continue;

            for (KnowledgeCard card : entry.getValue()) {
                if (card.getGroupId() != null) {
                    continue; // 已迁移
                }
                card.setGroupId(group.getId());
                knowledgeCardMapper.updateById(card);
                cardGroupMapper.updateCardCount(group.getId(), 1);
                migrated++;
            }
        }
        return migrated;
    }

    // ─── 内部方法 ───

    private List<GroupTreeNode> buildTree(List<CardGroup> allGroups) {
        Map<Long, List<CardGroup>> byParent = new LinkedHashMap<>();
        for (CardGroup g : allGroups) {
            byParent.computeIfAbsent(g.getParentId(), k -> new ArrayList<>()).add(g);
        }
        return buildChildren(null, byParent);
    }

    private List<GroupTreeNode> buildChildren(Long parentId, Map<Long, List<CardGroup>> byParent) {
        List<CardGroup> children = byParent.getOrDefault(parentId, List.of());
        List<GroupTreeNode> nodes = new ArrayList<>();
        for (CardGroup g : children) {
            List<GroupTreeNode> childNodes = buildChildren(g.getId(), byParent);
            nodes.add(new GroupTreeNode(g.getId(), g.getName(), g.getCardCount(), childNodes));
        }
        return nodes;
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > MAX_NAME_LENGTH ? s.substring(0, MAX_NAME_LENGTH) : s;
    }
}
