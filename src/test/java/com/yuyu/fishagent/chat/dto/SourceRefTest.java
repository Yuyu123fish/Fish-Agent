package com.yuyu.fishagent.chat.dto;

import com.yuyu.fishagent.chat.dto.SourceRef.Kind;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall.RecallHit;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall.RecallSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SourceRef（答案出处）映射 [v6.4 Top1 分类分组改进]：
 * 派生 4 类 kind（记忆/文档/卡片/公开）供前端分组，显示名优先文档名、文档去日期、按名去重 + 封顶。
 *
 * <p>关键：kind 在 SourceRef.from 里从现有 RecallHit 字段派生（sourceLabel + id "card:" 前缀），
 * 不改 RecallHit。卡片与私有文档 sourceLabel 都是"用户"，靠 id 前缀区分。</p>
 */
class SourceRefTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-27T06:00:00Z"), ZONE);

    @Test
    void memoryHitIsKindMemoryWithAge() {
        long createdAt = Instant.parse("2026-06-24T06:00:00Z").toEpochMilli(); // 3 天前
        RecallHit hit = new RecallHit("m1", "我用 Rust 维护 Fish-Agent", 0.9,
                RecallSource.TEXT, "记忆", 0.7, createdAt, null, null);

        SourceRef ref = SourceRef.from(hit, CLOCK);

        assertThat(ref.kind()).isEqualTo(Kind.MEMORY);
        assertThat(ref.memory()).isTrue();
        assertThat(ref.label()).isEqualTo("记忆");
        assertThat(ref.timeText()).isNotBlank(); // 相对年龄
    }

    @Test
    void userDocumentHitIsKindDocAndShowsDocName() {
        RecallHit hit = new RecallHit("k1", "第3章讲反向传播", 0.8,
                RecallSource.TEXT, "用户", 1.0, Instant.parse("2026-05-10T00:00:00Z").toEpochMilli(),
                "task-1", 3, "课程笔记.pdf");

        SourceRef ref = SourceRef.from(hit, CLOCK);

        assertThat(ref.kind()).isEqualTo(Kind.DOC);
        assertThat(ref.memory()).isFalse();
        assertThat(ref.label()).isEqualTo("课程笔记.pdf"); // 文档名优先于"用户"
        assertThat(ref.docId()).isEqualTo("task-1");
        assertThat(ref.timeText()).isEmpty(); // 文档不显示日期
    }

    @Test
    void cardHitIsKindCardEvenThoughSourceLabelIsUser() {
        // 卡片 sourceLabel 也是"用户"，但 id 带 "card:" 前缀 → CARD
        RecallHit hit = new RecallHit("card:42", "JVM 内存模型要点", 0.75,
                RecallSource.TEXT, "用户", 0.7, null, null, null, "JVM 内存模型");

        SourceRef ref = SourceRef.from(hit, CLOCK);

        assertThat(ref.kind()).isEqualTo(Kind.CARD);
        assertThat(ref.memory()).isFalse();
        assertThat(ref.label()).isEqualTo("JVM 内存模型"); // 卡片标题
    }

    @Test
    void publicHitIsKindPublic() {
        RecallHit hit = new RecallHit("p1", "公开规范内容", 0.7,
                RecallSource.TEXT, "公开", 1.0, null, "pub-1", 0, "行业规范.pdf");

        SourceRef ref = SourceRef.from(hit, CLOCK);

        assertThat(ref.kind()).isEqualTo(Kind.PUBLIC);
        assertThat(ref.label()).isEqualTo("行业规范.pdf");
    }

    @Test
    void longContentIsTruncatedWithEllipsis() {
        String longContent = "a".repeat(200);
        RecallHit hit = new RecallHit("k3", longContent, 0.5,
                RecallSource.TEXT, "用户", 1.0, null, "t", 0, "doc.pdf");

        SourceRef ref = SourceRef.from(hit, CLOCK);

        assertThat(ref.snippet()).hasSize(101).endsWith("…"); // 100 + 省略号（hover tooltip 用）
    }

    @Test
    void fromListDedupsByLabelKeepingHighestScore() {
        RecallHit d1a = new RecallHit("a", "片段1", 0.9, RecallSource.TEXT, "用户", 0.7,
                null, "t1", 0, "课程笔记.pdf");
        RecallHit d1b = new RecallHit("b", "片段2", 0.85, RecallSource.TEXT, "用户", 0.7,
                null, "t1", 1, "课程笔记.pdf");
        RecallHit d2 = new RecallHit("c", "片段3", 0.7, RecallSource.TEXT, "用户", 0.7,
                null, "t2", 0, "论文.pdf");

        List<SourceRef> refs = SourceRef.from(List.of(d1a, d1b, d2), CLOCK);

        assertThat(refs).hasSize(2); // 课程笔记.pdf(去重) + 论文.pdf
        assertThat(refs.get(0).label()).isEqualTo("课程笔记.pdf");
        assertThat(refs.get(0).snippet()).isEqualTo("片段1"); // 最高分那片
        assertThat(refs.get(1).label()).isEqualTo("论文.pdf");
        assertThat(refs).allSatisfy(r -> assertThat(r.kind()).isEqualTo(Kind.DOC));
    }

    @Test
    void fromListCapsAtMaxSources() {
        List<RecallHit> hits = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            hits.add(new RecallHit("id" + i, "c" + i, 0.9 - i * 0.01, RecallSource.TEXT,
                    "用户", 0.7, null, "t" + i, 0, "doc" + i + ".pdf"));
        }

        List<SourceRef> refs = SourceRef.from(hits, CLOCK);

        assertThat(refs).hasSize(5); // MAX_SOURCES
        assertThat(refs.get(0).label()).isEqualTo("doc0.pdf");
    }

    @Test
    void fromListHandlesEmptyAndNull() {
        assertThat(SourceRef.from(List.<RecallHit>of(), CLOCK)).isEmpty();
        assertThat(SourceRef.from((List<RecallHit>) null, CLOCK)).isEmpty();
    }
}
