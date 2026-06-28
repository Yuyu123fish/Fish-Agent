package com.yuyu.fishagent.card.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import com.yuyu.fishagent.card.mapper.KnowledgeCardMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识卡片 ES↔MySQL 最终一致性对账：定期回填缺 embedding / 缺失的 confirmed 文档，
 * 清理 ES 中已不存在于 MySQL 的孤儿文档。镜像 {@link com.yuyu.fishagent.rag.service.OrphanTaskCompensationService} 的调度形态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fish.card.es-reconcile.enabled", matchIfMissing = true)
public class KnowledgeCardEsReconciliationService {

    static final int BATCH = 2000;

    private final KnowledgeCardMapper knowledgeCardMapper;
    private final KnowledgeCardEsSyncService esSyncService;

    /** 对账差分结果：backfill 需重新同步（缺 embedding 或 ES 完全缺失），orphan 需从 ES 删除。 */
    record ReconciliationDiff(Set<Long> backfill, Set<Long> orphan) {
    }

    /** 纯集合差分：backfill = ES 缺 embedding ∪ (MySQL 有 ES 无)；orphan = ES 有 MySQL 无。 */
    static ReconciliationDiff diffReconciliation(Set<Long> mySqlConfirmedIds,
                                                 Set<Long> esConfirmedIds,
                                                 Set<Long> esMissingEmbeddingIds) {
        Set<Long> backfill = new LinkedHashSet<>();
        if (esMissingEmbeddingIds != null) {
            backfill.addAll(esMissingEmbeddingIds);
        }
        if (mySqlConfirmedIds != null) {
            for (Long id : mySqlConfirmedIds) {
                if (esConfirmedIds == null || !esConfirmedIds.contains(id)) {
                    backfill.add(id);
                }
            }
        }
        Set<Long> orphan = new LinkedHashSet<>();
        if (esConfirmedIds != null) {
            for (Long id : esConfirmedIds) {
                if (mySqlConfirmedIds == null || !mySqlConfirmedIds.contains(id)) {
                    orphan.add(id);
                }
            }
        }
        return new ReconciliationDiff(backfill, orphan);
    }

    /**
     * 每 2 分钟一次；单轮异常不影响后续触发。
     */
    @Scheduled(fixedDelay = 120_000L)
    public void reconcile() {
        try {
            runReconciliation();
        } catch (Exception e) {
            log.warn("[CardEsReconcile] 本轮调度失败（已吞，不影响主业务）: {}", e.getMessage());
        }
    }

    private void runReconciliation() {
        List<KnowledgeCard> cards = knowledgeCardMapper.selectList(Wrappers.<KnowledgeCard>lambdaQuery()
                .eq(KnowledgeCard::getStatus, KnowledgeCard.STATUS_CONFIRMED));
        Map<Long, KnowledgeCard> cardById = cards.stream()
                .collect(Collectors.toMap(KnowledgeCard::getId, c -> c, (a, b) -> a));
        Set<Long> mysqlIds = cardById.keySet();

        Set<Long> esIds = esSyncService.listConfirmedCardIds(BATCH);
        Set<Long> esMissing = esSyncService.listConfirmedMissingEmbeddingCardIds(BATCH);

        ReconciliationDiff diff = diffReconciliation(mysqlIds, esIds, esMissing);
        if (diff.backfill().isEmpty() && diff.orphan().isEmpty()) {
            return;
        }

        int backfilled = 0;
        for (Long id : diff.backfill()) {
            try {
                KnowledgeCard card = cardById.get(id);
                if (card != null) {
                    esSyncService.syncConfirmedQuietly(card);
                    backfilled++;
                }
            } catch (Exception e) {
                log.warn("[CardEsReconcile] 回填失败 cardId={}: {}", id, e.getMessage());
            }
        }
        int removed = 0;
        for (Long id : diff.orphan()) {
            try {
                esSyncService.deleteQuietly(id);
                removed++;
            } catch (Exception e) {
                log.warn("[CardEsReconcile] 删除孤儿失败 cardId={}: {}", id, e.getMessage());
            }
        }
        log.info("[CardEsReconcile] scanned={} backfilled={} removed={}", mysqlIds.size(), backfilled, removed);
    }
}
