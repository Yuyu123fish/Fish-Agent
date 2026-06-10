package com.yuyu.fishagent.card.controller;

import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.card.dto.BatchCardIdsRequest;
import com.yuyu.fishagent.card.dto.CardCreateRequest;
import com.yuyu.fishagent.card.dto.CardIdResponse;
import com.yuyu.fishagent.card.dto.CardPageVO;
import com.yuyu.fishagent.card.dto.CardMergeRequest;
import com.yuyu.fishagent.card.dto.CardRelationCreateRequest;
import com.yuyu.fishagent.card.dto.CardRelationVO;
import com.yuyu.fishagent.card.dto.CardStatsVO;
import com.yuyu.fishagent.card.dto.CardUpdateRequest;
import com.yuyu.fishagent.card.dto.CardVO;
import com.yuyu.fishagent.card.dto.ConfirmRelationRequest;
import com.yuyu.fishagent.card.dto.DiscoverResult;
import com.yuyu.fishagent.card.dto.GroupTreeNode;
import com.yuyu.fishagent.card.dto.ExtractRelationVO;
import com.yuyu.fishagent.card.dto.ExtractResult;
import com.yuyu.fishagent.card.service.CardExtractService;
import com.yuyu.fishagent.card.service.CardGroupService;
import com.yuyu.fishagent.card.service.CardRelationDiscoveryService;
import com.yuyu.fishagent.card.service.KnowledgeCardService;
import com.yuyu.fishagent.card.service.KeywordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识卡片 REST API。所有接口依赖全局鉴权拦截器写入 UserContextHolder。
 */
@RestController
@RequiredArgsConstructor
public class KnowledgeCardController {

    private final KnowledgeCardService knowledgeCardService;
    private final CardExtractService cardExtractService;
    private final CardGroupService cardGroupService;
    private final CardRelationDiscoveryService relationDiscoveryService;
    private final KeywordService keywordService;

    @PostMapping("/api/card/extract/{sessionId}")
    public ExtractResult extract(@PathVariable String sessionId) {
        return cardExtractService.extractFromSession(sessionId, requireUserId());
    }

    @PostMapping("/api/card")
    public CardIdResponse create(@RequestBody CardCreateRequest request) {
        return new CardIdResponse(knowledgeCardService.create(request));
    }

    @GetMapping("/api/card/list")
    public CardPageVO list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) String cardType,
            @RequestParam(required = false) Boolean reviewOverdue,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false, defaultValue = "desc") String sortOrder) {
        return knowledgeCardService.list(page, size, status, keyword, groupName, groupId,
                cardType, reviewOverdue, sortBy, sortOrder);
    }

    @GetMapping("/api/card/{id}")
    public CardVO detail(@PathVariable Long id) {
        return knowledgeCardService.detail(id);
    }

    @PutMapping("/api/card/{id}")
    public void update(@PathVariable Long id, @RequestBody CardUpdateRequest request) {
        knowledgeCardService.update(id, request);
    }

    @PutMapping("/api/card/{id}/confirm")
    public void confirm(@PathVariable Long id) {
        knowledgeCardService.batchConfirm(java.util.List.of(id));
    }

    @PutMapping("/api/card/batch-confirm")
    public void batchConfirm(@RequestBody BatchCardIdsRequest request) {
        knowledgeCardService.batchConfirm(request.ids());
    }

    @PutMapping("/api/card/batch-reject")
    public void batchReject(@RequestBody BatchCardIdsRequest request) {
        knowledgeCardService.batchReject(request.ids());
    }

    @PostMapping("/api/card/merge")
    public void merge(@RequestBody CardMergeRequest request) {
        knowledgeCardService.merge(request.keepId(), request.discardId());
    }

    @PostMapping("/api/card/{id}/relation")
    public CardIdResponse addRelation(@PathVariable Long id, @RequestBody CardRelationCreateRequest request) {
        return new CardIdResponse(knowledgeCardService.addRelation(id, request.toCardId(), request.relationType()));
    }

    @GetMapping("/api/card/{id}/relations")
    public java.util.List<CardRelationVO> relations(@PathVariable Long id) {
        return knowledgeCardService.relations(id);
    }

    @GetMapping("/api/card/all-relations")
    public java.util.List<ExtractRelationVO> allRelations() {
        return knowledgeCardService.allRelations();
    }

    @PostMapping("/api/card/discover-relations")
    public DiscoverResult discoverRelations() {
        return relationDiscoveryService.discoverRelations(requireUserId());
    }

    @PostMapping("/api/card/confirm-discovered-relations")
    public void confirmDiscoveredRelations(@RequestBody java.util.List<ConfirmRelationRequest> relations) {
        relationDiscoveryService.confirmDiscovered(requireUserId(), relations);
    }

    @PostMapping("/api/card/migrate-keywords")
    public java.util.Map<String, Object> migrateKeywords() {
        int migrated = keywordService.migrateExistingKeywords(requireUserId());
        return java.util.Map.of("success", true, "migrated", migrated);
    }

    @DeleteMapping("/api/card/relation/{id}")
    public void deleteRelation(@PathVariable Long id) {
        knowledgeCardService.deleteRelation(id);
    }

    @DeleteMapping("/api/card/{id}")
    public void delete(@PathVariable Long id) {
        knowledgeCardService.delete(id);
    }

    @GetMapping("/api/card/stats")
    public CardStatsVO stats() {
        return knowledgeCardService.stats();
    }

    @GetMapping("/api/card/groups")
    public java.util.List<GroupTreeNode> groups() {
        return cardGroupService.getUserGroupTree(requireUserId());
    }

    @PostMapping("/api/card/migrate-groups")
    public java.util.Map<String, Object> migrateGroups() {
        int migrated = cardGroupService.migrateExistingGroups(requireUserId());
        return java.util.Map.of("success", true, "migrated", migrated);
    }

    private static Long requireUserId() {
        Long userId = UserContextHolder.currentUserIdOrNull();
        if (userId == null) {
            throw new IllegalStateException("未登录");
        }
        return userId;
    }
}
