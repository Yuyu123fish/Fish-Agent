package com.yuyu.fishagent.chat.dto;

import com.yuyu.fishagent.rag.pipeline.recall.MemoryAgeLabel;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall.RecallHit;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 答案出处引用 [v6.4 Top1]：把 RAG 召回的 {@link RecallHit} 转成前端可渲染的来源引用。
 *
 * <p>个人 KB 每条事实都"有主"（你自己的某篇笔记/卡片/对话记忆），回答附出处是相对通用搜索
 * 最差异化的体验。设计要点：</p>
 * <ul>
 *   <li><b>按 {@link Kind} 分类</b>（记忆/文档/卡片/公开），供前端分组展示。kind 在 {@link #from(RecallHit)}
 *       里从现有字段派生（sourceLabel + id "card:" 前缀），<b>不改 RecallHit</b>——卡片与私有文档
 *       sourceLabel 都是"用户"，靠卡片 id 的 "card:" 前缀区分。</li>
 *   <li><b>显示文档名，不是分类</b>：label 优先文档名/卡片标题；记忆无文档名则用"记忆"。
 *       分类标签只留给模型注入上下文。</li>
 *   <li><b>文档不显示日期</b>（上传时间对单用户是噪声）；只对记忆保留相对年龄。</li>
 *   <li><b>按文档名去重 + 封顶 5</b>：同一文档多片只显示一个（保留最高分那片的 snippet）。</li>
 * </ul>
 *
 * @param label      显示名（文档名/卡片标题，或回退的分类如"记忆"/"公开"）
 * @param kind       分类（前端分组用）
 * @param docId      文档 id（记忆/卡片为 null）
 * @param chunkIndex 切片序号（记忆/卡片为 null）
 * @param snippet    内容预览（≤100 字，前端 hover tooltip 用）
 * @param memory     是否为对话记忆源（= kind==MEMORY，保留供前端样式兼容）
 * @param timeText   时间文本（仅记忆=相对年龄；文档为空串）
 */
public record SourceRef(String label, Kind kind, String docId, Integer chunkIndex,
                        String snippet, boolean memory, String timeText) {

    /** 来源分类，供前端分组。 */
    public enum Kind {
        MEMORY, DOC, CARD, PUBLIC
    }

    private static final int SNIPPET_MAX = 100;
    private static final int MAX_SOURCES = 5;
    private static final String MEMORY_LABEL = "记忆";

    public static SourceRef from(RecallHit hit) {
        return from(hit, Clock.systemDefaultZone());
    }

    /** 指定时钟，便于测试（记忆的相对年龄由时钟决定）。 */
    public static SourceRef from(RecallHit hit, Clock clock) {
        String sl = hit.sourceLabel();
        boolean isMemory = MEMORY_LABEL.equals(sl);
        Kind kind;
        if (isMemory) {
            kind = Kind.MEMORY;
        } else if (hit.id() != null && hit.id().startsWith("card:")) {
            kind = Kind.CARD;
        } else if ("公开".equals(sl) || "官方".equals(sl)) {
            kind = Kind.PUBLIC;
        } else {
            kind = Kind.DOC;
        }
        // 显示名优先文档名；记忆/无文档名时回退到分类标签
        String label = (hit.docName() != null && !hit.docName().isBlank())
                ? hit.docName().trim()
                : hit.effectiveSourceLabel();
        return new SourceRef(
                label,
                kind,
                hit.docId(),
                hit.chunkIndex(),
                snippet(hit.content()),
                isMemory,
                // 仅记忆显示相对年龄；文档不显示日期（上传时间对个人 KB 是噪声）
                isMemory ? MemoryAgeLabel.format(hit.createdAt(), clock) : ""
        );
    }

    public static List<SourceRef> from(List<RecallHit> hits) {
        return from(hits, Clock.systemDefaultZone());
    }

    public static List<SourceRef> from(List<RecallHit> hits, Clock clock) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        // hits 已按 score 降序：按显示名去重（同一文档多片只留最高分那片），最多 MAX_SOURCES 个
        List<SourceRef> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RecallHit h : hits) {
            SourceRef ref = from(h, clock);
            if (seen.add(ref.label)) {
                out.add(ref);
                if (out.size() >= MAX_SOURCES) {
                    break;
                }
            }
        }
        return out;
    }

    private static String snippet(String content) {
        if (content == null) {
            return "";
        }
        String flat = content.replace("\r\n", "\n").replace("\n", " ").trim();
        return flat.length() <= SNIPPET_MAX ? flat : flat.substring(0, SNIPPET_MAX) + "…";
    }
}
