# v3.4-1 检索重排序（RRF 融合 + DashScope Rerank）实施计划

> **For agentic workers:** 本计划面向「对本仓库零上下文」的工程师（如 Codex）。每个 Task 都给出确切文件路径、完整代码、测试与验证命令。逐 Task 执行，用复选框（`- [ ]`）跟踪进度。**禁止占位符**，所有代码均可直接落地。

**Goal:** 在现有 RAG 召回流水线「合并去重」之后，插入 **RRF 分数融合** 与 **DashScope Cross-Encoder Rerank** 两个阶段，把 BM25 / cosine 多路结果统一排名并精排，最终只取 Top-8 注入，失败自动降级。

**Architecture:** 召回不变（三索引 × 双路并发）→ 新增 `RagScoreFusion`（无状态 RRF 纯函数）把多组结果融合为候选池 Top-50 → 新增 `RagReranker`（DashScope `gte-rerank` API，含降级）精排为 Top-8 → 原 `renderBlock` 注入 SystemMessage。开关全开默认零配置（复用 `DASHSCOPE_API_KEY`），`rerank.enabled=false` 时行为回退到「RRF 截断」，`fusion.enabled=false` 时回退到旧的 `mergeByMaxScore`。

**Tech Stack:** Java 21（虚拟线程）、Spring Boot 3 / Spring AI Alibaba 1.1.2.0、Spring `RestClient`、JUnit 5 + AssertJ、Maven。

---

## 背景与现状（执行前必读）

源方案文档：`temp/v3.4-1-检索重排序.md`。请先通读。关键现状代码：

- 召回编排：`src/main/java/com/yuyu/fishagent/rag/pipeline/recall/RagRecall.java`
  - `RagRecall.RecallHit`：`record RecallHit(String id, String content, double score, RecallSource source)`。
  - `RagRecall.mergeByMaxScore(List<List<RecallHit>> batches, int maxFacts)` + `mergeFlatByMaxScore(...)`：当前的「去重 + 按 score 截断」逻辑。去重 key 规则：`id` 非空用 `id`，否则用 `"hash:" + Integer.toHexString(Objects.hash(content))`。
  - `DefaultAugmentation.buildAugmentation(...)`：第 194-229 行收集 `batches` → `mergeByMaxScore(batches, maxFacts)` → `renderBlock`。**这是要改造的接缝点。**
- 装配：`src/main/java/com/yuyu/fishagent/rag/pipeline/recall/RagRecallConfiguration.java`（`longTermRagContextService` Bean 构造 `DefaultAugmentation`）。
- 配置类：`src/main/java/com/yuyu/fishagent/rag/config/RagProperties.java`（已有嵌套 `Recall` / `Render`，本计划沿用「嵌套静态类」风格，新增 `Fusion` / `Rerank`，**不**单独建 `RagRerankProperties.java`——这是对源方案文件清单的合理偏离，与现有 `Recall`/`Render` 模式保持一致）。
- yaml：`src/main/resources/application.yml` 第 157-176 行 `fish.rag.*`。
- DashScope Key：`application.yml` 第 60 行 `spring.ai.dashscope.api-key: ${DASHSCOPE_API_KEY:}`。本计划让 `fish.rag.rerank.api-key` 同样绑定 `${DASHSCOPE_API_KEY:}`。
- 外部 HTTP 调用既有范式：`src/main/java/com/yuyu/fishagent/agent/tool/external/TavilySearchToolProvider.java`（用 `RestClient.builder()...`），新 Reranker 沿用此范式。
- 测试范式：`src/test/java/com/yuyu/fishagent/rag/pipeline/recall/LongTermRecallHitMergerTest.java`（JUnit5 + AssertJ，纯函数单测）。

**测试命令（Windows PowerShell）：** 单测跑指定类用 `mvn -q -Dtest=类名 test`；编译用 `mvn -q -DskipTests compile`。无 Maven Wrapper，直接用 `mvn`。

**提交规范：** 参照仓库历史，使用 `feat:` / `refactor:` / `test:` 前缀，中文描述正文可选。

---

## 文件结构总览（先锁定边界）

| 动作 | 文件 | 职责 |
|------|------|------|
| 修改 | `rag/pipeline/recall/RagRecall.java` | 抽出 `dedupKey()` 复用；`DefaultAugmentation` 增加 `RagReranker` 依赖并改造 `buildAugmentation` 接缝 |
| 新增 | `rag/pipeline/fusion/RagScoreFusion.java` | RRF 融合纯函数 |
| 新增 | `rag/pipeline/rerank/RagReranker.java` | Rerank 接口 + DashScope 实现 + 降级，含可单测的静态重排辅助方法 |
| 修改 | `rag/config/RagProperties.java` | 新增嵌套 `Fusion` / `Rerank`；调整 `Recall` 默认值 |
| 修改 | `rag/pipeline/recall/RagRecallConfiguration.java` | 注册 `RagReranker` Bean，注入 `DefaultAugmentation` |
| 修改 | `src/main/resources/application.yml` | 新增 `fish.rag.fusion.*` / `fish.rag.rerank.*`，调整 `recall` 默认值 |
| 新增 | `src/test/java/.../rag/pipeline/fusion/RagScoreFusionTest.java` | RRF 单测 |
| 新增 | `src/test/java/.../rag/pipeline/rerank/RagRerankerTest.java` | Reranker 重排/降级单测 |

依赖顺序：Task 1（配置）→ Task 2（RagRecall.dedupKey 重构）→ Task 3（RagScoreFusion）→ Task 4（RagReranker）→ Task 5（接线 RagRecall + Configuration）→ Task 6（yaml）→ Task 7（回归 & 收尾）。

---

## Task 1：扩展 RagProperties 配置（Fusion / Rerank + Recall 默认值）

**Files:**
- Modify: `src/main/java/com/yuyu/fishagent/rag/config/RagProperties.java`

- [ ] **Step 1：修改 `Recall` 默认值（per-subquery-size 5→10，knn-num-candidates 80→120）**

定位 `RagProperties.java` 中 `Recall` 内部类的两行，替换为：

```java
        /** 每个子查询在 ES 侧的 size / k。v3.4 扩大候选池：5 → 10。 */
        private int perSubquerySize = 10;

        /** 是否对用于检索的句子再做一条 kNN（需 EmbeddingModel）。 */
        private boolean vectorLegEnabled = true;

        /** kNN 的 num_candidates，建议 ≥ perSubquerySize。v3.4 扩大：80 → 120。 */
        private int knnNumCandidates = 120;
```

- [ ] **Step 2：新增 `fusion` / `rerank` 字段与嵌套类**

在 `RagProperties` 类体内，`private Render render = new Render();` 之后新增两个字段：

```java
    /** RRF 分数融合（统一 BM25 / cosine 排名）。 */
    private Fusion fusion = new Fusion();

    /** DashScope Cross-Encoder 精排。 */
    private Rerank rerank = new Rerank();
```

在 `Render` 静态类之后、类的最后一个 `}` 之前，新增两个嵌套类：

```java
    @Data
    public static class Fusion {

        /** RRF 融合开关；false 时回退到旧的 mergeByMaxScore。 */
        private boolean enabled = true;

        /** RRF 常数 k（标准值，一般不调）。 */
        private int rrfK = 60;

        /** 融合后候选池大小（喂给 Reranker）。 */
        private int candidatePoolSize = 50;
    }

    @Data
    public static class Rerank {

        /** Rerank 开关；false 时跳过精排，直接按融合结果截断 top-n。 */
        private boolean enabled = true;

        /** DashScope Rerank 模型名。 */
        private String model = "gte-rerank";

        /** 精排后保留条数（与 render.max-injected-facts 对齐）。 */
        private int topN = 8;

        /** API 调用超时秒数（连接与读取共用）。 */
        private int timeoutSeconds = 5;

        /** 失败（超时/限流/网络/空结果）时是否回退到融合结果。 */
        private boolean fallbackOnError = true;

        /** DashScope 服务根地址。 */
        private String baseUrl = "https://dashscope.aliyuncs.com";

        /** API Key；默认绑定 ${DASHSCOPE_API_KEY}，为空时 Reranker 自动降级。 */
        private String apiKey = "";
    }
```

- [ ] **Step 3：编译验证**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS（无编译错误）。

- [ ] **Step 4：提交**

```bash
git add src/main/java/com/yuyu/fishagent/rag/config/RagProperties.java
git commit -m "feat(rag): 新增 RRF 融合与 Rerank 配置项并扩大召回候选池"
```

---

## Task 2：在 RagRecall 抽出可复用的去重 key（DRY，供融合复用）

**Files:**
- Modify: `src/main/java/com/yuyu/fishagent/rag/pipeline/recall/RagRecall.java`
- Test: `src/test/java/com/yuyu/fishagent/rag/pipeline/recall/LongTermRecallHitMergerTest.java`（已存在，复用回归）

- [ ] **Step 1：新增静态方法 `dedupKey`，并让 `mergeFlatByMaxScore` 复用它**

在 `RagRecall` 类中 `mergeByMaxScore` 方法之前，新增：

```java
    /**
     * 命中去重 key：优先用 ES 文档 id，否则用 content 的稳定 hash。
     * <p>融合（{@link com.yuyu.fishagent.rag.pipeline.fusion.RagScoreFusion}）与
     * {@link #mergeFlatByMaxScore} 共用此规则，确保同一文档在两条路径上归并一致。</p>
     */
    public static String dedupKey(RecallHit h) {
        if (h == null || h.content() == null || h.content().isBlank()) {
            return null;
        }
        return h.id() != null && !h.id().isBlank()
                ? h.id()
                : "hash:" + Integer.toHexString(Objects.hash(h.content()));
    }
```

将 `mergeFlatByMaxScore` 内的去重逻辑改为复用 `dedupKey`：

```java
    public static List<RecallHit> mergeFlatByMaxScore(List<RecallHit> flat, int maxFacts) {
        Map<String, RecallHit> best = new LinkedHashMap<>();
        for (RecallHit h : flat) {
            String key = dedupKey(h);
            if (key == null) {
                continue;
            }
            best.merge(key, h, (a, b) -> a.score() >= b.score() ? a : b);
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(RecallHit::score).reversed())
                .limit(Math.max(0, maxFacts))
                .toList();
    }
```

- [ ] **Step 2：运行既有去重回归测试，确认行为不变**

Run: `mvn -q -Dtest=LongTermRecallHitMergerTest test`
Expected: PASS（2 个测试全绿，重构未改变语义）。

- [ ] **Step 3：提交**

```bash
git add src/main/java/com/yuyu/fishagent/rag/pipeline/recall/RagRecall.java
git commit -m "refactor(rag): 抽出 RagRecall.dedupKey 供融合与合并复用"
```

---

## Task 3：实现 RagScoreFusion（RRF 融合纯函数 · TDD）

**Files:**
- Create: `src/main/java/com/yuyu/fishagent/rag/pipeline/fusion/RagScoreFusion.java`
- Test: `src/test/java/com/yuyu/fishagent/rag/pipeline/fusion/RagScoreFusionTest.java`

RRF 公式：对每组结果按 `score` 降序，组内排名 `rank` 从 0 开始；某文档融合分 `= Σ 1 / (k + rank + 1)`，跨组求和。返回时把融合分写回代表命中的 `score` 字段（代表命中取「组内原始 score 最高」的那条，保留其 `content`），按融合分降序截断到 `poolSize`。

- [ ] **Step 1：先写失败测试**

Create `src/test/java/com/yuyu/fishagent/rag/pipeline/fusion/RagScoreFusionTest.java`:

```java
package com.yuyu.fishagent.rag.pipeline.fusion;

import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RagScoreFusionTest {

    private static RagRecall.RecallHit hit(String id, double score, RagRecall.RecallSource src) {
        return new RagRecall.RecallHit(id, "content-" + id, score, src);
    }

    @Test
    void fuseByRrfRanksByReciprocalRankNotRawScore() {
        // 文本路 BM25 尺度（0~15），向量路 cosine 尺度（0~1）
        List<RagRecall.RecallHit> textLeg = List.of(
                hit("A", 12.0, RagRecall.RecallSource.TEXT),
                hit("B", 8.0, RagRecall.RecallSource.TEXT));
        List<RagRecall.RecallHit> vectorLeg = List.of(
                hit("B", 0.90, RagRecall.RecallSource.VECTOR),
                hit("A", 0.30, RagRecall.RecallSource.VECTOR));

        List<RagRecall.RecallHit> fused = RagScoreFusion.fuseByRrf(
                List.of(textLeg, vectorLeg), 60, 10);

        // A: 文本 rank0 + 向量 rank1 = 1/61 + 1/62
        // B: 文本 rank1 + 向量 rank0 = 1/62 + 1/61  →  与 A 相同，验证两者都进池且分数相等
        assertThat(fused).hasSize(2);
        double expected = 1.0 / 61 + 1.0 / 62;
        assertThat(fused.get(0).score()).isCloseTo(expected, within(1e-9));
        assertThat(fused.get(1).score()).isCloseTo(expected, within(1e-9));
    }

    @Test
    void fuseByRrfDedupsByKeyAndKeepsContent() {
        List<RagRecall.RecallHit> g1 = List.of(hit("X", 5.0, RagRecall.RecallSource.TEXT));
        List<RagRecall.RecallHit> g2 = List.of(hit("X", 0.7, RagRecall.RecallSource.VECTOR));
        List<RagRecall.RecallHit> fused = RagScoreFusion.fuseByRrf(List.of(g1, g2), 60, 10);
        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).content()).isEqualTo("content-X");
        // 同一文档命中两组：1/(60+1) * 2
        assertThat(fused.get(0).score()).isCloseTo(2.0 / 61, within(1e-9));
    }

    @Test
    void fuseByRrfLimitsToPoolSize() {
        List<RagRecall.RecallHit> g = List.of(
                hit("A", 9, RagRecall.RecallSource.TEXT),
                hit("B", 8, RagRecall.RecallSource.TEXT),
                hit("C", 7, RagRecall.RecallSource.TEXT));
        List<RagRecall.RecallHit> fused = RagScoreFusion.fuseByRrf(List.of(g), 60, 2);
        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).id()).isEqualTo("A");
        assertThat(fused.get(1).id()).isEqualTo("B");
    }

    @Test
    void fuseByRrfSkipsBlankAndNullGroups() {
        List<RagRecall.RecallHit> g = new java.util.ArrayList<>();
        g.add(hit("A", 1, RagRecall.RecallSource.TEXT));
        g.add(new RagRecall.RecallHit("", "   ", 5, RagRecall.RecallSource.TEXT)); // blank content
        List<List<RagRecall.RecallHit>> batches = new java.util.ArrayList<>();
        batches.add(null);
        batches.add(g);
        List<RagRecall.RecallHit> fused = RagScoreFusion.fuseByRrf(batches, 60, 10);
        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).id()).isEqualTo("A");
    }
}
```

- [ ] **Step 2：运行测试确认失败（类不存在）**

Run: `mvn -q -Dtest=RagScoreFusionTest test`
Expected: 编译失败 / FAIL —— `RagScoreFusion` 未定义。

- [ ] **Step 3：实现 RagScoreFusion**

Create `src/main/java/com/yuyu/fishagent/rag/pipeline/fusion/RagScoreFusion.java`:

```java
package com.yuyu.fishagent.rag.pipeline.fusion;

import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion：不依赖原始分数，只看各路组内排名，统一 BM25 与 cosine 的不可比尺度。
 * <p>无状态纯函数，便于单测与在编排层直接调用。</p>
 */
public final class RagScoreFusion {

    private RagScoreFusion() {
    }

    /**
     * 对多组召回结果做 RRF 融合。
     *
     * @param batches  多组结果（每组来自一路 searcher × 一条子查询 × 文本/向量腿）
     * @param rrfK     RRF 常数 k（标准 60）
     * @param poolSize 融合后候选池大小上限
     * @return 按融合分降序的候选列表；融合分写入 {@link RagRecall.RecallHit#score()}，content 取组内原始分最高的代表命中
     */
    public static List<RagRecall.RecallHit> fuseByRrf(List<List<RagRecall.RecallHit>> batches,
                                                      int rrfK,
                                                      int poolSize) {
        int k = Math.max(1, rrfK);
        Map<String, Double> fusedScore = new LinkedHashMap<>();
        Map<String, RagRecall.RecallHit> representative = new LinkedHashMap<>();

        if (batches != null) {
            for (List<RagRecall.RecallHit> group : batches) {
                if (group == null || group.isEmpty()) {
                    continue;
                }
                List<RagRecall.RecallHit> sorted = group.stream()
                        .filter(h -> RagRecall.dedupKey(h) != null)
                        .sorted(Comparator.comparingDouble(RagRecall.RecallHit::score).reversed())
                        .toList();
                for (int rank = 0; rank < sorted.size(); rank++) {
                    RagRecall.RecallHit h = sorted.get(rank);
                    String key = RagRecall.dedupKey(h);
                    fusedScore.merge(key, 1.0 / (k + rank + 1), Double::sum);
                    representative.merge(key, h, (a, b) -> a.score() >= b.score() ? a : b);
                }
            }
        }

        List<RagRecall.RecallHit> out = new ArrayList<>(fusedScore.size());
        for (Map.Entry<String, Double> e : fusedScore.entrySet()) {
            RagRecall.RecallHit rep = representative.get(e.getKey());
            out.add(new RagRecall.RecallHit(rep.id(), rep.content(), e.getValue(), rep.source()));
        }
        return out.stream()
                .sorted(Comparator.comparingDouble(RagRecall.RecallHit::score).reversed())
                .limit(Math.max(0, poolSize))
                .toList();
    }
}
```

- [ ] **Step 4：运行测试确认通过**

Run: `mvn -q -Dtest=RagScoreFusionTest test`
Expected: PASS（4 个测试全绿）。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/yuyu/fishagent/rag/pipeline/fusion/RagScoreFusion.java src/test/java/com/yuyu/fishagent/rag/pipeline/fusion/RagScoreFusionTest.java
git commit -m "feat(rag): 新增 RagScoreFusion RRF 分数融合"
```

---

## Task 4：实现 RagReranker（DashScope 精排 + 降级 · TDD）

**Files:**
- Create: `src/main/java/com/yuyu/fishagent/rag/pipeline/rerank/RagReranker.java`
- Test: `src/test/java/com/yuyu/fishagent/rag/pipeline/rerank/RagRerankerTest.java`

设计：`RagReranker` 为接口，唯一方法 `rerank(query, candidates, topN)`。实现 `DashScopeRagReranker` 内部自带降级——`enabled=false` 或 `apiKey` 空白或异常时，返回 `candidates` 截断到 `topN`，因此编排层**总是**调用 `rerank`，无需在编排层写 if/else。响应解析逻辑抽成静态方法 `reorderByResults`，便于脱离 HTTP 单测。

- [ ] **Step 1：先写失败测试（只测纯逻辑：重排映射 + 降级，不发真实 HTTP）**

Create `src/test/java/com/yuyu/fishagent/rag/pipeline/rerank/RagRerankerTest.java`:

```java
package com.yuyu.fishagent.rag.pipeline.rerank;

import com.yuyu.fishagent.rag.config.RagProperties;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagRerankerTest {

    private static RagRecall.RecallHit hit(String id) {
        return new RagRecall.RecallHit(id, "content-" + id, 1.0, RagRecall.RecallSource.TEXT);
    }

    private static List<RagRecall.RecallHit> candidates() {
        return List.of(hit("A"), hit("B"), hit("C"), hit("D"));
    }

    @Test
    void reorderByResultsAppliesApiOrderAndScoreAndTopN() {
        // API 把 index=2(C) 放第一，index=0(A) 第二
        List<Map<String, Object>> results = List.of(
                Map.of("index", 2, "relevance_score", 0.95),
                Map.of("index", 0, "relevance_score", 0.80),
                Map.of("index", 1, "relevance_score", 0.40));
        List<RagRecall.RecallHit> out = DashScopeRagReranker.reorderByResults(candidates(), results, 2);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).id()).isEqualTo("C");
        assertThat(out.get(0).score()).isEqualTo(0.95);
        assertThat(out.get(1).id()).isEqualTo("A");
        assertThat(out.get(1).score()).isEqualTo(0.80);
    }

    @Test
    void reorderByResultsIgnoresOutOfRangeIndex() {
        List<Map<String, Object>> results = List.of(
                Map.of("index", 9, "relevance_score", 0.99),
                Map.of("index", 1, "relevance_score", 0.50));
        List<RagRecall.RecallHit> out = DashScopeRagReranker.reorderByResults(candidates(), results, 8);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).id()).isEqualTo("B");
    }

    @Test
    void rerankReturnsTruncatedCandidatesWhenDisabled() {
        RagProperties props = new RagProperties();
        props.getRerank().setEnabled(false);
        DashScopeRagReranker reranker = new DashScopeRagReranker(props);
        List<RagRecall.RecallHit> out = reranker.rerank("q", candidates(), 2);
        assertThat(out).extracting(RagRecall.RecallHit::id).containsExactly("A", "B");
    }

    @Test
    void rerankReturnsTruncatedCandidatesWhenApiKeyBlank() {
        RagProperties props = new RagProperties();
        props.getRerank().setEnabled(true);
        props.getRerank().setApiKey("   ");
        DashScopeRagReranker reranker = new DashScopeRagReranker(props);
        List<RagRecall.RecallHit> out = reranker.rerank("q", candidates(), 3);
        assertThat(out).extracting(RagRecall.RecallHit::id).containsExactly("A", "B", "C");
    }

    @Test
    void rerankReturnsEmptyForEmptyCandidates() {
        RagProperties props = new RagProperties();
        DashScopeRagReranker reranker = new DashScopeRagReranker(props);
        assertThat(reranker.rerank("q", List.of(), 5)).isEmpty();
    }

    @Test
    void rerankPropagatesExceptionWhenFallbackDisabled() {
        RagProperties props = new RagProperties();
        props.getRerank().setEnabled(true);
        props.getRerank().setApiKey("test-key-that-will-fail");
        props.getRerank().setFallbackOnError(false);
        // baseUrl 默认指向真实 DashScope 地址 → 连接/解析必失败，触发 throw e 路径
        DashScopeRagReranker reranker = new DashScopeRagReranker(props);
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> reranker.rerank("test query", candidates(), 3));
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run: `mvn -q -Dtest=RagRerankerTest test`
Expected: 编译失败 —— `RagReranker` / `DashScopeRagReranker` 未定义。

- [ ] **Step 3：实现接口与 DashScope 实现**

Create `src/main/java/com/yuyu/fishagent/rag/pipeline/rerank/RagReranker.java`:

```java
package com.yuyu.fishagent.rag.pipeline.rerank;

import com.yuyu.fishagent.rag.config.RagProperties;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cross-Encoder 精排：对融合后的候选池调用 DashScope Rerank API，截取 Top-N 注入。
 */
public interface RagReranker {

    /**
     * @param query      原始（或重写后的）用户查询
     * @param candidates 融合后的候选池（已按 RRF 降序）
     * @param topN       精排后保留条数
     * @return 精排后的命中列表（长度 ≤ topN）；任何失败均降级为 candidates 的前 topN 条
     */
    List<RagRecall.RecallHit> rerank(String query, List<RagRecall.RecallHit> candidates, int topN);

    /**
     * DashScope {@code gte-rerank} 实现，自带降级（关闭 / 无 Key / 异常 / 空结果 → 回退融合结果）。
     * <p>HTTP 范式对齐 {@code TavilySearchToolProvider}（{@link RestClient}）。</p>
     */
    @Slf4j
    final class DashScopeRagReranker implements RagReranker {

        private static final String RERANK_PATH = "/api/v1/services/aigc/text-rerank/text-rerank";

        private final RagProperties ragProperties;
        private final RestClient restClient;

        public DashScopeRagReranker(RagProperties ragProperties) {
            this.ragProperties = ragProperties;
            this.restClient = buildClient(ragProperties.getRerank());
        }

        @Override
        public List<RagRecall.RecallHit> rerank(String query, List<RagRecall.RecallHit> candidates, int topN) {
            int limit = Math.max(1, topN);
            List<RagRecall.RecallHit> fallback = truncate(candidates, limit);

            RagProperties.Rerank cfg = ragProperties.getRerank();
            if (candidates == null || candidates.isEmpty() || query == null || query.isBlank()) {
                return fallback;
            }
            if (!cfg.isEnabled() || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
                return fallback;
            }

            try {
                List<String> documents = candidates.stream()
                        .map(RagRecall.RecallHit::content)
                        .toList();

                @SuppressWarnings("unchecked")
                Map<String, Object> resp = restClient.post()
                        .uri(RERANK_PATH)
                        .body(Map.of(
                                "model", cfg.getModel(),
                                "input", Map.of(
                                        "query", query,
                                        "documents", documents),
                                "parameters", Map.of(
                                        "top_n", limit,
                                        "return_documents", false)))
                        .retrieve()
                        .body(Map.class);

                List<Map<String, Object>> results = extractResults(resp);
                if (results.isEmpty()) {
                    log.warn("[RagReranker] Rerank 返回空结果，降级到融合结果 candidates={}", candidates.size());
                    return fallback;
                }
                List<RagRecall.RecallHit> reranked = reorderByResults(candidates, results, limit);
                return reranked.isEmpty() ? fallback : reranked;
            } catch (Exception e) {
                if (cfg.isFallbackOnError()) {
                    log.warn("[RagReranker] Rerank 调用失败，降级到融合结果: {}", e.getMessage());
                    return fallback;
                }
                throw e;
            }
        }

        private static RestClient buildClient(RagProperties.Rerank cfg) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            Duration timeout = Duration.ofSeconds(Math.max(1, cfg.getTimeoutSeconds()));
            factory.setConnectTimeout(timeout);
            factory.setReadTimeout(timeout);
            return RestClient.builder()
                    .baseUrl(cfg.getBaseUrl())
                    .requestFactory(factory)
                    .defaultHeader("Authorization", "Bearer " + cfg.getApiKey())
                    .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .build();
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> extractResults(Map<String, Object> resp) {
            if (resp == null) {
                return List.of();
            }
            Object output = resp.get("output");
            if (!(output instanceof Map<?, ?> outputMap)) {
                return List.of();
            }
            Object results = ((Map<String, Object>) outputMap).get("results");
            if (results instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
            return List.of();
        }

        /**
         * 按 API 返回的 {@code index} 重排候选，写入 {@code relevance_score}，越界 index 跳过，截断到 topN。
         */
        static List<RagRecall.RecallHit> reorderByResults(List<RagRecall.RecallHit> candidates,
                                                          List<Map<String, Object>> results,
                                                          int topN) {
            List<RagRecall.RecallHit> out = new ArrayList<>();
            for (Map<String, Object> r : results) {
                if (out.size() >= Math.max(1, topN)) {
                    break;
                }
                Object idxObj = r.get("index");
                if (!(idxObj instanceof Number idxNum)) {
                    continue;
                }
                int idx = idxNum.intValue();
                if (idx < 0 || idx >= candidates.size()) {
                    continue;
                }
                RagRecall.RecallHit base = candidates.get(idx);
                double score = r.get("relevance_score") instanceof Number n ? n.doubleValue() : base.score();
                out.add(new RagRecall.RecallHit(base.id(), base.content(), score, base.source()));
            }
            return out;
        }

        private static List<RagRecall.RecallHit> truncate(List<RagRecall.RecallHit> candidates, int limit) {
            if (candidates == null || candidates.isEmpty()) {
                return List.of();
            }
            return candidates.stream().limit(limit).toList();
        }
    }
}
```

- [ ] **Step 4：运行测试确认通过**

Run: `mvn -q -Dtest=RagRerankerTest test`
Expected: PASS（6 个测试全绿）。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/yuyu/fishagent/rag/pipeline/rerank/RagReranker.java src/test/java/com/yuyu/fishagent/rag/pipeline/rerank/RagRerankerTest.java
git commit -m "feat(rag): 新增 RagReranker（DashScope gte-rerank 精排 + 降级）"
```

---

## Task 5：接线 RagRecall 与 RagRecallConfiguration

**Files:**
- Modify: `src/main/java/com/yuyu/fishagent/rag/pipeline/recall/RagRecall.java`
- Modify: `src/main/java/com/yuyu/fishagent/rag/pipeline/recall/RagRecallConfiguration.java`

- [ ] **Step 1：在 RagRecall 增加 import 与 RagReranker 字段**

在 `RagRecall.java` 顶部 import 区，`import com.yuyu.fishagent.rag.config.RagProperties;` 之后新增：

```java
import com.yuyu.fishagent.rag.pipeline.fusion.RagScoreFusion;
import com.yuyu.fishagent.rag.pipeline.rerank.RagReranker;
```

在 `DefaultAugmentation` 类的字段区，`private final ExecutorService recallExecutor;` 之后新增：

```java
        private final RagReranker reranker;
```

- [ ] **Step 2：在 DefaultAugmentation 构造器追加 reranker 参数**

将构造器签名与赋值改为（在 `recallExecutor` 之后追加 `RagReranker reranker`）：

```java
        public DefaultAugmentation(
                RagProperties ragProperties,
                RagQueryRewrite.QueryRewriter queryRewriter,
                RagQueryExpand.SubQueryExpander subQueryExpander,
                DocumentSearcher userMemorySearcher,
                DocumentSearcher userKnowledgeSearcher,
                DocumentSearcher publicKnowledgeSearcher,
                ObjectProvider<ElasticsearchOperations> operationsProvider,
                @Qualifier("ragRecallExecutor") ExecutorService recallExecutor,
                RagReranker reranker) {
            this.ragProperties = ragProperties;
            this.queryRewriter = queryRewriter;
            this.subQueryExpander = subQueryExpander;
            this.userMemorySearcher = userMemorySearcher;
            this.userKnowledgeSearcher = userKnowledgeSearcher;
            this.publicKnowledgeSearcher = publicKnowledgeSearcher;
            this.operationsProvider = operationsProvider;
            this.recallExecutor = recallExecutor;
            this.reranker = reranker;
        }
```

- [ ] **Step 3：改造 buildAugmentation 的合并接缝（融合 → 精排）**

定位现有的合并段落（约第 220-229 行）：

```java
            int maxFacts = Math.max(1, ragProperties.getRender().getMaxInjectedFacts());
            List<RecallHit> merged = mergeByMaxScore(batches, maxFacts);
            if (merged.isEmpty()) {
                log.debug("[RagRecall] 无召回命中 sid={}", sessionId);
                return Optional.empty();
            }

            String block = renderBlock(merged);
            log.debug("[RagRecall] 注入 sid={}, hits={}, blockLen={}", sessionId, merged.size(), block.length());
            return Optional.of(block);
```

整体替换为（融合得到候选池 → Reranker 精排 → 渲染）：

```java
            int maxFacts = Math.max(1, ragProperties.getRender().getMaxInjectedFacts());
            int poolSize = Math.max(maxFacts, ragProperties.getFusion().getCandidatePoolSize());

            // 候选池：RRF 融合（统一 BM25/cosine 排名）；关闭时回退到旧的 max-score 合并
            List<RecallHit> candidates = ragProperties.getFusion().isEnabled()
                    ? RagScoreFusion.fuseByRrf(batches, ragProperties.getFusion().getRrfK(), poolSize)
                    : mergeByMaxScore(batches, poolSize);
            if (candidates.isEmpty()) {
                log.debug("[RagRecall] 无召回命中 sid={}", sessionId);
                return Optional.empty();
            }

            // 精排：Reranker 自带降级（关闭/无 Key/异常 → 返回候选前 topN）
            int topN = Math.min(maxFacts, Math.max(1, ragProperties.getRerank().getTopN()));
            List<RecallHit> finalHits = reranker.rerank(textForExpandAndVector, candidates, topN);
            if (finalHits.isEmpty()) {
                log.debug("[RagRecall] 精排后无命中 sid={}", sessionId);
                return Optional.empty();
            }

            String block = renderBlock(finalHits);
            log.debug("[RagRecall] 注入 sid={}, candidatePool={}, finalHits={}, blockLen={}",
                    sessionId, candidates.size(), finalHits.size(), block.length());
            return Optional.of(block);
```

> 说明：`topN` 取 `min(maxFacts, rerank.topN)`，保证注入条数不超过 `render.max-injected-facts`；`renderBlock` 仍按 `max-injected-chars` 做字符级二次截断。

- [ ] **Step 4：在 Configuration 注册 RagReranker Bean 并注入**

修改 `RagRecallConfiguration.java`：顶部新增 import：

```java
import com.yuyu.fishagent.rag.pipeline.rerank.RagReranker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
```

在 `ragRecallExecutor` Bean 之后新增 Reranker Bean（加 `@ConditionalOnProperty` 守卫，`FISH_RAG_ENABLED=false` 时不创建）：

```java
    @Bean
    @ConditionalOnProperty(prefix = "fish.rag", name = "enabled", havingValue = "true")
    public RagReranker ragReranker(RagProperties ragProperties) {
        return new RagReranker.DashScopeRagReranker(ragProperties);
    }
```

在 `longTermRagContextService` 方法参数末尾追加 `RagReranker ragReranker`，并在 `new RagRecall.DefaultAugmentation(...)` 末尾追加 `ragReranker`：

```java
    @Bean
    public RagRecall.Augmentation longTermRagContextService(
            RagProperties ragProperties,
            RagQueryRewrite.QueryRewriter queryRewriter,
            RagQueryExpand.SubQueryExpander subQueryExpander,
            UserMemoryElasticsearchSearcher userMemoryElasticsearchSearcher,
            UserKnowledgeElasticsearchSearcher userKnowledgeElasticsearchSearcher,
            PublicKnowledgeElasticsearchSearcher publicKnowledgeElasticsearchSearcher,
            ObjectProvider<ElasticsearchOperations> operationsProvider,
            @Qualifier("ragRecallExecutor") ExecutorService ragRecallExecutor,
            RagReranker ragReranker) {
        return new RagRecall.DefaultAugmentation(
                ragProperties,
                queryRewriter,
                subQueryExpander,
                userMemoryElasticsearchSearcher,
                userKnowledgeElasticsearchSearcher,
                publicKnowledgeElasticsearchSearcher,
                operationsProvider,
                ragRecallExecutor,
                ragReranker);
    }
```

- [ ] **Step 5：编译 + 跑相关单测**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

Run: `mvn -q -Dtest=RagScoreFusionTest,RagRerankerTest,LongTermRecallHitMergerTest test`
Expected: PASS（全部绿）。

- [ ] **Step 6：提交**

```bash
git add src/main/java/com/yuyu/fishagent/rag/pipeline/recall/RagRecall.java src/main/java/com/yuyu/fishagent/rag/pipeline/recall/RagRecallConfiguration.java
git commit -m "feat(rag): RagRecall 接入 RRF 融合与 Reranker 精排"
```

---

## Task 6：补充 application.yml 配置

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1：更新 recall 默认值并新增 fusion / rerank 段**

定位第 157-176 行 `fish.rag` 段，替换 `recall` 的两个值并在 `render` 段之后追加 `fusion` / `rerank`：

```yaml
  # ── RAG 检索增强 ───────────────────────────────────────────
  rag:
    enabled: ${FISH_RAG_ENABLED:true}
    rewrite-enabled: ${FISH_RAG_REWRITE_ENABLED:false}
    # 查询重写（仅 rewrite-enabled=true 时生效）：NONE | CHAT_MODEL
    rewrite-provider: CHAT_MODEL
    rewrite-temperature: 0.1
    rewrite-max-tokens: 256
    rewrite-max-chars: 512
    # 多查询召回（v3.4 扩大候选池）
    recall:
      max-sub-queries: 12
      min-token-chars: 1
      per-subquery-size: 10
      vector-leg-enabled: true
      knn-num-candidates: 120
    # 结果渲染上限
    render:
      max-injected-facts: 8
      max-injected-chars: 4000
    # RRF 分数融合（统一 BM25 / cosine 排名）
    fusion:
      enabled: ${FISH_RAG_FUSION_ENABLED:true}
      rrf-k: 60
      candidate-pool-size: 50
    # DashScope Cross-Encoder 精排（复用 DASHSCOPE_API_KEY，无 Key 自动降级）
    rerank:
      enabled: ${FISH_RAG_RERANK_ENABLED:true}
      model: ${FISH_RAG_RERANK_MODEL:gte-rerank}
      top-n: 8
      timeout-seconds: 5
      fallback-on-error: true
      base-url: https://dashscope.aliyuncs.com
      api-key: ${DASHSCOPE_API_KEY:}
```

- [ ] **Step 2：启动期配置绑定校验（编译 + 上下文加载冒烟）**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

> 若仓库存在 `@SpringBootTest` 上下文加载测试，可顺带运行确认配置绑定无误；否则以 Task 7 全量测试为准。

- [ ] **Step 3：提交**

```bash
git add src/main/resources/application.yml
git commit -m "feat(rag): application.yml 补充 fusion/rerank 配置并扩大召回参数"
```

---

## Task 7：回归验证与收尾

**Files:** 无新增改动，仅验证。

- [ ] **Step 1：全量编译**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 2：运行本特性全部单测**

Run: `mvn -q -Dtest=RagScoreFusionTest,RagRerankerTest,LongTermRecallHitMergerTest test`
Expected: PASS（融合 4 + 精排 6 + 合并回归 2 = 12 个测试全绿）。

- [ ] **Step 3：验收点逐条核对（对照源方案「六、验收标准」）**

- [ ] 验收 1：`fish.rag.rerank.enabled=false` 时跳过精排，直接按融合结果截断 `top-n`（由 `DashScopeRagReranker` 降级分支保证，单测 `rerankReturnsTruncatedCandidatesWhenDisabled` 覆盖）。
- [ ] 验收 2：`enabled=true` 且有 Key 时，按 `relevance_score` 排序（`reorderByResults` 单测覆盖映射逻辑）。
- [ ] 验收 3：超时 / 失败时 `fallback-on-error=true` 自动降级，不向对话流抛异常（`rerank` catch 分支）。
- [ ] 验收 4：RRF 融合后文本路与向量路的高质量结果都能进入候选池（`fuseByRrfRanksByReciprocalRankNotRawScore` 单测覆盖）。
- [ ] 验收 5：所有新增配置项均有默认值，零配置（仅 `DASHSCOPE_API_KEY`）即可用（`RagProperties` 默认值 + yaml 默认）。

- [ ] **Step 4：（可选）真实链路手测**

设置环境变量 `DASHSCOPE_API_KEY` 后启动应用，发起一轮带知识检索的对话，确认日志出现：

```
[RagRecall] 注入 sid=..., candidatePool=NN, finalHits=8, blockLen=...
```

并观察未出现 `[RagReranker] Rerank 调用失败` WARN（出现也仅代表降级，不影响对话）。

- [ ] **Step 5：最终确认无遗留改动**

Run: `git status`
Expected: 工作区干净，所有改动已分 Task 提交。

---

## 自检（Self-Review）

- **Spec 覆盖：** 源方案三大问题（分数不可比 / 无精排 / 召回量小）分别由 Task 3（RRF）、Task 4（Rerank）、Task 1+6（per-subquery-size 10 / knn 120 / 候选池 50）覆盖；五条验收标准在 Task 7 Step 3 逐条对应。
- **类型一致性：** 全程使用既有 `RagRecall.RecallHit(id, content, score, source)`；融合写入 `score` 字段、精排写入 `relevance_score`；`fuseByRrf(batches, rrfK, poolSize)`、`rerank(query, candidates, topN)`、`reorderByResults(candidates, results, topN)`、`RagRecall.dedupKey(hit)` 在各 Task 中签名保持一致。
- **降级闭环：** 编排层始终调用 `reranker.rerank(...)`，所有降级集中在实现内部，避免编排层散落 if/else。
- **偏离记录：** 源方案文件清单中的 `RagRerankProperties.java` 改为 `RagProperties` 内嵌套 `Rerank` 类（与既有 `Recall`/`Render` 风格一致），属合理工程决策。
- **审查修正：** RestClient 从「每次 rerank 重建」改为「构造时创建一次复用」（P1）；补充 `fallbackOnError=false` 异常传播路径测试（P2）。P3（`@ConditionalOnProperty`）经二次审查发现 `ChatService` 直接注入 `Augmentation` Bean，若 `ragReranker` 有条件注册而 `longTermRagContextService` 无条件注册，`FISH_RAG_ENABLED=false` 时 Spring 找不到 `RagReranker` → `NoSuchBeanDefinitionException` 启动崩溃。P3 回退，内部 `enabled` 检查已足够。

---

_计划版本：基于 v3.4-1 检索重排序设计 · 生成于 2026-06-05_
