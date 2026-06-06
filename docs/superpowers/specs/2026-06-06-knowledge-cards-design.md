# 知识卡片（Knowledge Cards）功能设计

> 日期：2026-06-06
> 版本：v4.1
> 范围：前后端全栈新功能，不涉及现有功能修改（除 RAG recall 扩展）

---

## 1. 概述

### 1.1 定位

将散落在对话中的知识沉淀为可复用的结构化卡片，通过 AI 自动提取 + 用户手动创建双通道生成，支持卡片间关联和图谱可视化，并反馈到 RAG 管线提升 AI 回答质量。

### 1.2 核心价值

- **知识沉淀**：对话中的知识点不再随会话结束而丢失
- **关联发现**：AI 自动推理知识点之间的关系，构建个人知识网络
- **RAG 增强**：确认后的卡片参与 RAG 检索，让 AI 基于用户已有知识回答
- **复习巩固**：内置复习模式，帮助用户巩固已整理的知识

### 1.3 MVP 范围

- 卡片 CRUD + MySQL/ES 双写存储
- AI 自动提取（用户主动触发 + 被动提示，带即时预览确认）
- 用户手动创建
- 局部关联展示 + vis-network 全局图谱可视化
- 批量确认/拒绝、卡片合并、卡片分组
- 复习模式（纯前端）
- RAG recall 扩展检索知识卡片索引
- 空状态引导页
- 知识库切片可视化（KnowledgeView 内查看文档切片）

---

## 2. 整体架构 & 数据流

```
┌─────────────────────────────────────────────────────────┐
│                      前端 (Vue 3)                        │
│                                                         │
│  ChatView                                                │
│    ├─ 对话 ≥8 轮 → 被动提示"提取知识卡片"                  │
│    └─ 点击提取 → POST /api/card/extract/{sessionId}      │
│       └─ 返回后弹出即时预览面板（勾选 + 编辑 + 确认）       │
│                                                         │
│  KnowledgeCardView                                       │
│    ├─ 空状态引导页（首次无卡片时展示）                      │
│    ├─ 顶部统计概览条                                      │
│    ├─ 操作栏：搜索 / 筛选 / 分组Tab / 视图切换 / 手动创建   │
│    ├─ 卡片视图：3列网格（含批量操作）                       │
│    ├─ 图谱视图：vis-network 力导向图                       │
│    ├─ 复习模式：逐张翻转复习                               │
│    ├─ CardDetailPanel（右侧滑出详情，含疑似重复提示+合并）   │
│    └─ CardCreateDialog（创建/编辑对话框，含分组选择）        │
└──────────────┬──────────────────────────────────────────┘
               │ REST API
┌──────────────▼──────────────────────────────────────────┐
│                   后端 (Spring Boot)                      │
│                                                         │
│  KnowledgeCardController                                │
│    ├─ POST   /api/card/extract/{sessionId}   AI提取      │
│    ├─ GET    /api/card/list                  分页+筛选    │
│    ├─ GET    /api/card/{id}                  卡片详情     │
│    ├─ POST   /api/card                       手动创建     │
│    ├─ PUT    /api/card/{id}                  编辑卡片     │
│    ├─ PUT    /api/card/{id}/confirm          确认卡片     │
│    ├─ PUT    /api/card/batch-confirm         批量确认     │
│    ├─ PUT    /api/card/batch-reject          批量拒绝     │
│    ├─ DELETE /api/card/{id}                  删除卡片     │
│    ├─ POST   /api/card/{id}/relation         添加关联     │
│    ├─ DELETE /api/card/relation/{id}         删除关联     │
│    ├─ GET    /api/card/{id}/relations        关联列表     │
│    ├─ POST   /api/card/merge                合并卡片     │
│    └─ GET    /api/card/stats                统计概览     │
│                                                         │
│  KnowledgeCardService                                   │
│    ├─ CRUD + 确认/拒绝/批量操作/合并                       │
│    └─ MySQL 写入 + ES 同步（confirmed 写入，deleted 移除）  │
│                                                         │
│  CardExtractService                                     │
│    ├─ extractFromSession()                              │
│    │   ├─ 加载对话历史 → 拼 prompt → 调 LLM              │
│    │   ├─ 解析 JSON → 写 MySQL（status=pending）          │
│    │   ├─ 建内部关联（同批次卡片间）                       │
│    │   ├─ 建外部关联（embedding → ES 向量检索 → 匹配已有卡片）│
│    │   └─ 返回提取结果（含完整卡片数据，供即时预览）         │
│    └─ 生成 embedding（复用 DashScope text-embedding-v2）  │
│                                                         │
│  RAG Recall 扩展                                         │
│    └─ UserKnowledgeCardSearcher                         │
│        ├─ 检索 fish-knowledge-card 索引                   │
│        ├─ 作为 RagRecall 并行检索源之一                    │
│        └─ 结果参与后续 rerank + fusion 流程               │
└──────────────┬──────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────┐
│                    存储层                                 │
│                                                         │
│  MySQL                                                   │
│    ├─ knowledge_card（卡片主表，含 group_name 分组字段）    │
│    └─ card_relation（关联表，含 confidence 置信度）         │
│                                                         │
│  Elasticsearch                                           │
│    └─ fish-knowledge-card（索引）                         │
│        ├─ 全文检索 (title + content + keywords)           │
│        ├─ 向量检索 (1536-dim embedding, cosine)           │
│        └─ 仅写入 status=confirmed 的卡片                   │
└─────────────────────────────────────────────────────────┘
```

---

## 3. 数据模型

### 3.1 MySQL 表

#### knowledge_card（卡片主表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT AUTO_INCREMENT | 主键 |
| `user_id` | BIGINT NOT NULL | 所属用户 |
| `title` | VARCHAR(200) | 卡片标题 |
| `content` | TEXT | 卡片正文（Markdown） |
| `keywords` | JSON | 关键词数组，如 `["JVM","内存","GC"]` |
| `card_type` | VARCHAR(20) | `concept`（概念）/ `topic`（主题），AI 决定 |
| `source_type` | VARCHAR(20) | `chat`（对话提取）/ `manual`（手动）/ `knowledge`（知识库提取） |
| `source_id` | VARCHAR(100) | 来源标识：sessionId 或 documentId，NULL 为手动创建 |
| `status` | VARCHAR(20) DEFAULT 'pending' | `pending` / `confirmed` / `rejected` |
| `group_name` | VARCHAR(100) | 分组名称（如"Java 基础"），NULL 表示未分组 |
| `created_at` | DATETIME | 创建时间 |
| `updated_at` | DATETIME | 更新时间 |

索引：
- `idx_user_status` (user_id, status)
- `idx_source` (source_type, source_id)
- `idx_user_group` (user_id, group_name)

分组说明：`group_name` 是轻量分组方案（非新表），AI 提取时自动建议分组名，手动创建时用户可选填。卡片列表顶部按分组名聚合为 Tab 筛选。

#### card_relation（关联表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT AUTO_INCREMENT | 主键 |
| `from_card_id` | BIGINT NOT NULL | 源卡片 |
| `to_card_id` | BIGINT NOT NULL | 目标卡片 |
| `relation_type` | VARCHAR(30) | 关系类型（见下方枚举） |
| `confidence` | FLOAT | AI 置信度 0~1，手动创建为 1.0 |
| `created_at` | DATETIME | 创建时间 |

关系类型枚举（MVP 4 种）：
- `related_to` — 相关（最通用）
- `contains` — 包含（A 包含 B，如"Java 基础"→"JVM 内存模型"）
- `precedes` — 前置（学 B 前先学 A）
- `derived_from` — 衍生自（A 从 B 讨论中衍生）

索引：
- `idx_from` (from_card_id)
- `idx_to` (to_card_id)
- UNIQUE `uk_relation` (from_card_id, to_card_id, relation_type)

### 3.2 Elasticsearch 索引

#### fish-knowledge-card

```json
{
  "mappings": {
    "properties": {
      "cardId":       { "type": "long" },
      "userId":       { "type": "long" },
      "title":        { "type": "text", "analyzer": "ik_max_word" },
      "content":      { "type": "text", "analyzer": "ik_max_word" },
      "keywords":     { "type": "text", "analyzer": "ik_max_word" },
      "cardType":     { "type": "keyword" },
      "sourceType":   { "type": "keyword" },
      "status":       { "type": "keyword" },
      "groupName":    { "type": "keyword" },
      "embedding":    { "type": "dense_vector", "dims": 1536, "index": true, "similarity": "cosine" },
      "createdAt":    { "type": "date" }
    }
  }
}
```

写入规则：
- 仅 `status = confirmed` 的卡片写入/更新 ES
- pending / rejected 不进索引
- 卡片被拒绝或删除时从 ES 移除
- ES 索引同时服务于：卡片页全文搜索、关联匹配时向量检索、RAG recall 知识检索

---

## 4. AI 提取引擎

### 4.1 触发方式

**主路径**：用户在 ChatView 点击「提取知识卡片」按钮，传入 sessionId。

**被动提示**：对话满足以下条件时，聊天区底部显示非阻塞提示：
- 当前会话 ≥ 8 轮
- 对话中包含知识性关键词（"概念"、"原理"、"流程"、"区别"、"机制"、"架构"等）

提示样式：`💡 这段对话包含多个知识点，点击提取为知识卡片`。点击后走正常提取流程，不点则消失。

### 4.2 对话加载策略（上下文窗口保护）

直接加载全部对话原文有挤爆 LLM 上下文窗口的风险。采用分级策略：

| 对话长度 | 策略 | Token 预算 |
|----------|------|-----------|
| ≤ 10 轮 或估计 ≤ 4000 tokens | 直接使用全部原文 | 不限 |
| \> 10 轮 | 先调 LLM 对前半段生成「对话摘要」（复用 `memory/compress` 模块），再将「摘要 + 最近 10 轮原文」拼给提取 prompt | 约 6000 tokens |

**具体流程**（长对话）：

```
1. 加载全部 user + assistant 消息（跳过 tool 类型）
2. 估算 token 数（按中文 1 字 ≈ 1.5 token 粗算）
3. 若 ≤ 4000 tokens → 直接拼 prompt
4. 若 > 4000 tokens：
   a. 取前半段消息 → 调 LLM 生成 300 字以内的「对话摘要」
   b. 取最近 10 轮原文
   c. 拼接格式：
      """
      [对话摘要]
      以下是前半段对话的概要：
      {summary}

      [近期对话原文]
      {recent_10_turns}
      """
   d. 将拼接内容喂给提取 prompt
```

**设计理由**：摘要保留了整体语境（对话讨论了什么话题、得出了什么结论），近期原文保留了细节（具体的技术要点、概念解释），两者结合比只用摘要或只用近期对话的提取质量都高。

### 4.3 LLM Prompt

**输入**：4.2 策略处理后的对话内容

**Prompt 核心规则**：

```
你是一个知识提取专家。分析以下对话，提取其中的知识点。

规则：
1. 每个知识点生成一张卡片
2. 简单概念用 card_type: "concept"，复杂主题用 "topic"
3. title 简洁（≤30字），content 用 Markdown 格式，200字以内
4. keywords 提取 3-6 个关键标签
5. 如果知识点之间存在关联，用 relations 指出
6. 关系类型只能是：related_to / contains / precedes / derived_from
7. from_title 和 to_title 必须是同一批提取中某张卡片的 title
8. 根据卡片主题，建议一个 group_name（如"Java 基础"、"Spring 框架"），相同领域的卡片用同一 group_name

输出严格 JSON：
{
  "cards": [
    {
      "title": "...",
      "content": "...",
      "keywords": ["...", "..."],
      "card_type": "concept" | "topic",
      "group_name": "..."
    }
  ],
  "relations": [
    {
      "from_title": "卡片A的title",
      "to_title": "卡片B的title",
      "relation_type": "related_to",
      "confidence": 0.85
    }
  ]
}
```

### 4.4 后端处理流程

```
extractFromSession(sessionId, userId)
  │
  ├─ 1. 从 chat_history 加载该 session 全部对话（user + assistant，跳过 tool）
  │
  ├─ 2. 对话加载策略（见 4.2）
  │     ├─ 短对话（≤10 轮 / ≤4000 tokens）→ 直接使用原文
  │     └─ 长对话 → 生成摘要 + 取最近 10 轮原文拼接
  │
  ├─ 3. 拼接提取 prompt + 处理后的对话内容 → 调 LLM
  │
  ├─ 4. 解析 LLM 返回的 JSON
  │     └─ 校验：title 非空、content 非空、relation 的 from_title/to_title 存在
  │
  ├─ 5. 批量写入 knowledge_card 表（status=pending）
  │     └─ 记录 title → cardId 映射
  │
  ├─ 6. 建立内部关联（同批次卡片间的 relations）
  │     └─ from_title/to_title → 查映射得 cardId → 写 card_relation
  │
  ├─ 7. 建立外部关联（与用户已有 confirmed 卡片的关联）
  │     ├─ 对每张新卡片生成 embedding（复用 DashScope text-embedding-v2）
  │     ├─ 用 embedding 在 ES fish-knowledge-card 做向量检索（top 5）
  │     ├─ 相似度 > 0.75 的已有卡片 → 自动建 related_to 关联
  │     └─ 写 card_relation，confidence = similarity score
  │
  └─ 8. 返回提取结果 { extractedCount, cardIds[], cards[] }
        └─ cards[] 包含每张卡片的完整数据（id, title, content, keywords,
           cardType, groupName, relations），供前端即时预览
```

### 4.5 错误处理

- LLM 返回非 JSON → 记录日志，返回错误"提取失败，请稍后重试"
- JSON 解析成功但部分卡片校验失败 → 跳过无效卡片，保留有效的
- ES 向量检索失败 → 跳过外部关联步骤，不影响主流程

---

## 5. REST API

### 5.1 卡片 CRUD

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/api/card/extract/{sessionId}` | AI 提取 | — | `ExtractResult` |
| GET | `/api/card/list` | 分页列表 | Query: `page, size, status, keyword, groupName` | `{records[], total}` |
| GET | `/api/card/{id}` | 卡片详情（含关联） | — | `CardVO` |
| POST | `/api/card` | 手动创建 | `CardCreateRequest` | `{id}` |
| PUT | `/api/card/{id}` | 编辑卡片 | `CardUpdateRequest` | — |
| PUT | `/api/card/{id}/confirm` | 确认（pending→confirmed，同步写 ES） | — | — |
| PUT | `/api/card/batch-confirm` | 批量确认 | `{ids[]}` | — |
| PUT | `/api/card/batch-reject` | 批量拒绝 | `{ids[]}` | — |
| DELETE | `/api/card/{id}` | 删除（同步删 ES + 关联） | — | — |

### 5.2 关联操作

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/api/card/{id}/relation` | 添加关联 | `{toCardId, relationType}` | `{id}` |
| DELETE | `/api/card/relation/{id}` | 删除关联 | — | — |
| GET | `/api/card/{id}/relations` | 获取关联列表 | — | `CardRelationVO[]` |

### 5.3 合并操作

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/api/card/merge` | 合并两张卡片 | `{keepId, discardId}` | — |

合并逻辑：保留 keepId 的标题/内容，合并双方 keywords（去重），discardId 的所有关联转移到 keepId，删除 discardId 及其关联。

### 5.4 统计接口

| 方法 | 路径 | 说明 | 响应 |
|------|------|------|------|
| GET | `/api/card/stats` | 卡片统计概览 | `CardStatsVO` |

```json
{
  "total": 42,
  "confirmed": 35,
  "pending": 7,
  "relationCount": 28,
  "weekNew": 12,
  "groups": [
    { "name": "Java 基础", "count": 15 },
    { "name": "Spring 框架", "count": 10 },
    { "name": "数据库", "count": 8 }
  ]
}
```

### 5.5 关键 DTO

**ExtractResult**（提取结果，供前端即时预览）：
```json
{
  "extractedCount": 5,
  "cardIds": [1, 2, 3, 4, 5],
  "cards": [
    {
      "id": 1,
      "title": "JVM 内存模型",
      "content": "JVM 内存分为...",
      "keywords": ["JVM", "内存"],
      "cardType": "concept",
      "groupName": "Java 基础"
    }
  ],
  "relations": [
    {
      "fromCardId": 1,
      "toCardId": 2,
      "relationType": "related_to",
      "confidence": 0.85
    }
  ]
}
```

**CardVO**（卡片详情响应）：
```json
{
  "id": 1,
  "title": "JVM 内存模型",
  "content": "JVM 内存分为...",
  "keywords": ["JVM", "内存", "GC"],
  "cardType": "topic",
  "sourceType": "chat",
  "sourceId": "session-xxx",
  "status": "confirmed",
  "groupName": "Java 基础",
  "relations": [
    {
      "id": 1,
      "cardId": 5,
      "cardTitle": "Java 垃圾回收机制",
      "relationType": "related_to",
      "confidence": 0.89,
      "direction": "outgoing"
    }
  ],
  "createdAt": "2026-06-06T10:00:00",
  "updatedAt": "2026-06-06T10:00:00"
}
```

**CardListItemVO**（列表项）：
```json
{
  "id": 1,
  "title": "JVM 内存模型",
  "contentPreview": "JVM 内存分为五个区域...",
  "keywords": ["JVM", "内存", "GC"],
  "cardType": "topic",
  "sourceType": "chat",
  "status": "confirmed",
  "groupName": "Java 基础",
  "relationCount": 3,
  "createdAt": "2026-06-06T10:00:00"
}
```

### 5.6 权限控制

- 所有 `/api/card/**` 需要登录
- 用户只能操作自己的卡片（`WHERE user_id = currentUserId`）
- 知识卡片为个人知识管理，管理员无额外权限

---

## 6. 后端包结构 & RAG 扩展

### 6.1 新增 card 包

新增 `com.yuyu.fishagent.card` 包，与 `chat`、`rag`、`memory` 平级：

```
card/
├── controller/
│   └── KnowledgeCardController.java
├── entity/
│   ├── KnowledgeCard.java
│   └── CardRelation.java
├── dto/
│   ├── CardVO.java
│   ├── CardListItemVO.java
│   ├── CardRelationVO.java
│   ├── CardCreateRequest.java
│   ├── CardUpdateRequest.java
│   ├── CardStatsVO.java
│   ├── ExtractResult.java
│   └── CardMergeRequest.java
├── mapper/
│   ├── KnowledgeCardMapper.java
│   └── CardRelationMapper.java
└── service/
    ├── KnowledgeCardService.java       // CRUD + 确认/批量/合并 + ES 同步
    └── CardExtractService.java         // AI 提取 + LLM 调用 + 关联匹配
```

**依赖关系**：
- `CardExtractService` → 复用 `llm` 模块调 LLM
- `CardExtractService` → 复用 `rag` 模块的 embedding 生成（DashScope text-embedding-v2）
- `CardExtractService` → 直接操作 ES（写索引 + 向量检索）
- `KnowledgeCardService` → 操作 MySQL（mapper）+ ES（确认/删除时同步）

### 6.2 RAG Recall 扩展

新增 `UserKnowledgeCardSearcher` 于 `rag/pipeline/recall/` 下：

```
rag/pipeline/recall/
├── ...（现有文件不变）
└── UserKnowledgeCardSearcher.java     // 检索 fish-knowledge-card 索引
```

- 逻辑与 `UserKnowledgeElasticsearchSearcher` 类似
- 在 `RagRecall` 中注册为并行检索源之一
- 检索范围：当前用户的 confirmed 卡片
- 检索结果参与后续 rerank + fusion 流程
- 效果：AI 回答时可引用用户自己的知识卡片，形成"提取 → 确认 → AI 使用 → 新对话 → 再提取"的闭环

---

## 7. 前端设计

### 7.1 新增路由 & 导航

- 路由：`/cards` → `KnowledgeCardView.vue`
- 导航入口（3 处）：
  1. AppHeader：知识库按钮旁新增 📋 知识卡片按钮
  2. ChatView：输入区新增「提取知识卡片」按钮
  3. DrawerSidebar：侧栏菜单新增「知识卡片」入口

### 7.2 新增文件

| 文件 | 职责 |
|------|------|
| `src/views/KnowledgeCardView.vue` | 知识卡片主页面（空状态引导 / 统计栏 / 操作栏 / 卡片网格 / 图谱视图 / 复习模式） |
| `src/components/CardGrid.vue` | 卡片网格视图（3列响应式网格 + 批量操作栏） |
| `src/components/CardDetailPanel.vue` | 右侧滑出详情面板（420px + 关联列表 + 疑似重复提示 + 合并入口） |
| `src/components/CardCreateDialog.vue` | 手动创建/编辑对话框（标题 + Markdown textarea + 关键词 + 类型 + 分组选择） |
| `src/components/CardGraphView.vue` | vis-network 图谱可视化（力导向布局 + 节点交互 + 关系筛选 + 全屏） |
| `src/components/CardReviewMode.vue` | 复习模式（逐张翻转 + 忘了/模糊/熟悉） |
| `src/components/CardExtractPreview.vue` | 提取即时预览面板（勾选 + 行内编辑 + 确认/取消） |
| `src/components/EmptyCardGuide.vue` | 空状态引导组件（双卡片入口 + 使用贴士） |
| `src/api/card.ts` | 卡片相关 API 调用 |
| `src/composables/useCardDetail.ts` | 详情面板开关状态（类似 useDrawer） |

### 7.3 修改文件

| 文件 | 变更 |
|------|------|
| `src/router/index.ts` | 新增 `/cards` 路由 |
| `src/components/AppHeader.vue` | 新增 📋 知识卡片导航按钮 |
| `src/components/DrawerSidebar.vue` | 侧栏菜单新增知识卡片入口 |
| `src/views/ChatView.vue` | 输入区新增「提取知识卡片」按钮 + 被动提示逻辑（≥8轮 + 知识性关键词） |

### 7.4 页面布局

#### 空状态引导页

首次进入（无任何卡片）时展示引导界面，替代空白列表：

```
┌───────────────────────────────────────────┐
│            🧠 知识卡片                     │
│                                           │
│     "把散落在对话中的知识，沉淀为可复用的卡片" │
│                                           │
│     ┌──────────────┐  ┌──────────────┐    │
│     │  💬 从对话提取  │  │  ✏️ 手动创建   │    │
│     │              │  │              │    │
│     │ 去聊天页完成   │  │ 直接写下你    │    │
│     │ 一段对话，然后 │  │ 掌握的知识点   │    │
│     │ 点击提取按钮   │  │              │    │
│     └──────────────┘  └──────────────┘    │
│                                           │
│     📊 使用小贴士：                         │
│     1. 和 AI 深入讨论一个话题后，点击提取     │
│     2. 确认后的卡片会帮助 AI 更好地回答问题   │
│     3. 关联的卡片越多，知识图谱越有价值       │
└───────────────────────────────────────────┘
```

条件：当 `GET /api/card/stats` 返回 `total === 0` 时展示。有卡片后永远不再显示。

#### KnowledgeCardView 主页面（有数据时）

```
┌─────────────────────────────────────────────────────┐
│ AppHeader (📋 知识卡片)                               │
├─────────────────────────────────────────────────────┤
│                                                     │
│  📊 总计 42 | ✅ 已确认 35 | ⏳ 待确认 7 | 🔗 关联 28  │
│     📅 本周新增 12                                   │
│                                                     │
│  [搜索框]  [筛选: 全部▼]  [+ 手动创建]  [🎯 复习]  [刷新]│
│  [全部 | Java 基础 | Spring 框架 | 数据库 | ...]       │
│                                                     │
│  [卡片视图] [图谱视图]                                │
│                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │ 卡片 #1   │  │ 卡片 #2   │  │ 卡片 #3   │          │
│  │ 标题      │  │ 标题      │  │ 标题      │          │
│  │ 摘要预览…  │  │ 摘要预览…  │  │ 摘要预览…  │          │
│  │ [标签]    │  │ [标签]    │  │ [标签]    │          │
│  │ 🟢已确认  │  │ 🟡待确认  │  │ 🟢已确认  │          │
│  └──────────┘  └──────────┘  └──────────┘          │
│                                                     │
│  [分页器]                                            │
│                                                     │
└─────────────────────────────────────────────────────┘
```

#### 卡片网格样式

- **布局**：3 列网格（响应式：窄屏 2 列，手机 1 列）
- **卡片**：毛玻璃风格（`var(--bg-glass)` + `backdrop-filter: blur`），与现有 UI 风格统一
- **待确认卡片**：左侧 3px 黄色竖条 + 淡黄背景，底部「确认 ✓」「编辑 ✎」「拒绝 ✗」按钮
- **已确认卡片**：底部「编辑」「删除」按钮
- **标签**：关键词用小 tag 展示，最多 3 个，超出显示 `+N`
- **类型徽章**：右上角小徽章，concept 显示"概念"，topic 显示"主题"

#### 批量操作

勾选多张卡片后底部浮出操作条：
- `确认选中 (N)` / `拒绝选中 (N)`
- 顶部快捷按钮 `全部确认` / `全部拒绝`（针对当前筛选结果中的 pending 卡片）

#### 卡片详情面板（右侧滑出）

点击任意卡片 → 从右侧滑出 420px 详情面板：

```
┌─────────────────────────────────┐
│ ← 返回列表          编辑  删除    │
├─────────────────────────────────┤
│                                 │
│  # JVM 内存模型            [主题] │
│                                 │
│  ## 内容                        │
│  （Markdown 渲染）               │
│                                 │
│  关键词：[JVM] [内存] [GC]        │
│  分组：Java 基础                  │
│  来源：💬 对话 #xxx → 跳转        │
│                                 │
├─────────────────────────────────┤
│  🔗 关联卡片 (3)                 │
│                                 │
│  ┌─ Java 垃圾回收机制             │
│  │  关系：相关  置信度：0.89       │
│  ├─ Java 基础                    │
│  │  关系：包含 →  置信度：0.92     │
│  │  💡 疑似重复（confidence 0.93） │
│  │     [合并到此卡片]              │
│  └─ JVM 类加载机制               │
│     关系：相关  置信度：0.76       │
│                                 │
│  [+ 添加关联]                    │
└─────────────────────────────────┘
```

面板特性：
- 滑入动画：`translateX(100%) → 0`，300ms ease-out
- 背景遮罩：`rgba(0,0,0,0.3)`
- Content 渲染复用 `.markdown-body` 样式
- 关联列表：每项显示目标卡片标题 + 关系类型标签 + 置信度
- 疑似重复检测：confidence > 0.9 的关联标记为"💡 疑似重复"，提供「合并到此卡片」按钮，调 `POST /api/card/merge`

#### 图谱视图

使用 vis-network 实现力导向图，与 tsParticles 粒子背景视觉语言一致：

**节点样式**：
- 概念卡片：小圆圈，40px，填充 `var(--primary-dim)` `#4f46e5`
- 主题卡片：大圆圈，60px，填充渐变 `var(--gradient-brand)`
- 选中节点：外圈发光 `var(--glow-primary)`
- 节点内显示标题（字数过多截断）
- 悬浮显示完整标题 + 内容摘要 tooltip

**连线样式**（按关系类型区分）：
- `related_to` → 灰色虚线
- `contains` → 蓝色实线
- `precedes` → 紫色实线 + 箭头
- `derived_from` → 绿色实线 + 箭头

**交互**：
- 单击节点 → 弹出浮层（标题 + 摘要 + 跳转详情按钮）
- 双击节点 → 打开右侧详情面板
- 拖拽节点 → 力导向自动重布局
- 滚轮缩放 + 拖拽画布平移
- 筛选：按关系类型过滤连线
- 全屏按钮：画布撑满页面

**数据加载**：前端调 `GET /api/card/list`（status=confirmed，不分页取全部）+ 关联数据，前端组装为 vis-network nodes + edges。MVP 阶段前端全量加载（个人卡片量级在几十到几百）。

#### 复习模式

- 卡片页顶部 🎯「复习」按钮
- 逐张展示：正面显示标题 → 用户点击翻转显示内容
- 每张底部三个按钮：`忘了` / `模糊` / `熟悉`
- 简单轮次逻辑：标记"忘了"的会在本轮后面重新出现，"熟悉"的跳过
- MVP 纯前端逻辑，不做持久化复习记录

#### 提取即时预览

提取 API 返回后，前端弹出预览面板（非跳转，在 ChatView 内弹出）：

```
┌── AI 提取了 5 张知识卡片 ──────────────────┐
│                                           │
│  ✅ JVM 内存模型          [概念]  ✏️ 编辑   │
│  ✅ Java 垃圾回收机制      [主题]  ✏️ 编辑   │
│  ✅ 堆内存分配策略         [概念]  ✏️ 编辑   │
│  ☐ Spring Bean 生命周期    [主题]  ✏️ 编辑   │
│  ✅ GC Roots 概念         [概念]  ✏️ 编辑   │
│                                           │
│  🔗 检测到 3 条关联关系                     │
│                                           │
│       [全部确认]    [取消]                  │
└───────────────────────────────────────────┘
```

- 默认全选，用户可取消勾选不想要的
- 点击编辑可当场修改标题/内容/关键词
- 全部确认：勾选的卡片直接变为 confirmed（跳过 pending），同步写 ES
- 取消：所有卡片留在 pending 状态，用户可稍后在卡片页处理

---

## 8. 新增依赖

### 前端

```json
"vis-network": "^9.x",
"vis-data": "^7.x"
```

### 后端

无新增外部依赖。复用现有：
- LLM 调用：`llm` 模块
- Embedding 生成：DashScope text-embedding-v2（复用 `rag` 模块）
- ES 操作：现有 Elasticsearch client
- MySQL 操作：MyBatis

---

## 9. 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 生成方式 | 自动提取 + 手动创建 | 双通道覆盖主要场景 |
| 卡片粒度 | 混合（AI 按复杂度选 concept/topic） | 避免一刀切的粒度问题 |
| 图谱实现 | 渐进式（先局部关联 + 全局图谱可视化） | 局部关联开发简单，图谱可视化提升体验 |
| 存储方案 | MySQL + ES 双写 | 与现有 RAG 架构一致 |
| 提取触发 | 用户主动 + 被动提示 | 平衡控制感和可发现性 |
| 提取后流程 | 即时预览 → 勾选确认 | 趁热打铁，减少后续确认摩擦 |
| 卡片分组 | group_name 字段（非新表） | 轻量，MVP 够用，避免过度建模 |
| RAG 联动 | confirmed 卡片参与 RAG recall | 闭环：卡片 → AI 回答 → 新对话 → 再提取 |
| 图谱库 | vis-network | 力导向开箱即用，与暗色主题搭，API 简洁 |
| 复习模式 | 纯前端一轮过 | MVP 轻量，后续可加间隔重复算法 |
| 疑似重复 | confidence > 0.9 标记 + 合并 | AI 多次提取可能重复，需去重机制 |
| 空状态引导 | 引导页替代空白 | 新用户首次进入无卡片时会流失 |
| 对话加载策略 | 分级：短对话原文 / 长对话摘要+近期 | 保护 LLM 上下文窗口不被挤爆 |
| 知识库切片可视化 | 两级结构（概览→主题分组→切片）+ 切片↔卡片双向关联 | 避免切片数量爆炸，建立原始知识与结构化知识的桥梁 |
| 切片主题分组 | 切片 embedding → K-Means 聚类 → 缓存结果 | 无额外存储，基于现有 embedding 动态计算 |
| 切片↔卡片关联 | 双向 embedding 向量检索（动态计算） | 无额外存储，关联自动随卡片增减而更新 |

---

## 10. 知识库切片可视化

### 10.1 背景

用户上传文档后，系统将其解析为切片（chunks）存入 ES。但用户目前只能看到"处理成功，120 个切片"这样的抽象信息，无法了解切片的实际内容。增加切片可视化，让用户对知识库的内部状态有直观认知，并与知识卡片建立双向导航。

### 10.2 两级浏览结构

一个文档可能有上百个切片，直接平铺会挤爆用户视角。采用 **两级结构**：文档概览 → 主题分组 → 展开查看切片。

#### 第一级：文档概览

在 KnowledgeView 的文档表格中，每行（status=SUCCESS）新增「查看切片」按钮。点击后弹出右侧滑出面板：

```
┌─────────────────────────────────────┐
│ ← 返回                  JVM详解.pdf   │
├─────────────────────────────────────┤
│                                     │
│  📄 文档概览                         │
│  切片数：47 | 文件大小：2.3 MB        │
│                                     │
│  📝 AI 摘要（首次打开时生成并缓存）     │
│  "本文档涵盖了 JVM 架构、内存模型、    │
│   GC 机制和类加载四个核心主题..."      │
│                                     │
├─────────────────────────────────────┤
│  📂 主题分组 (5)                     │
│                                     │
│  ├─ JVM 基础架构         8 个切片     │
│  ├─ 内存模型与区域划分    12 个切片    │
│  ├─ 垃圾回收机制          11 个切片    │
│  ├─ 类加载机制            9 个切片     │
│  └─ JVM 调优参数          7 个切片     │
│                                     │
│  [🔍 搜索切片内容]                    │
└─────────────────────────────────────┘
```

#### 第二级：展开主题分组

点击某个主题分组后，展示该分组下的切片列表：

```
┌─────────────────────────────────────┐
│ ← 返回文档概览                        │
├─────────────────────────────────────┤
│  📂 内存模型与区域划分 (12)            │
├─────────────────────────────────────┤
│                                     │
│  ┌─ 切片 #12                        │
│  │  JVM 内存分为五个区域：堆、栈...    │
│  │  342 字 | 🔗 2 张关联卡片          │
│  ├─ 切片 #13                        │
│  │  堆内存是 JVM 中最大的内存区域...   │
│  │  287 字 | 🔗 1 张关联卡片          │
│  ├─ 切片 #14                        │
│  │  方法区用于存储已被虚拟机加载的...   │
│  │  415 字 | 无关联卡片               │
│  └─ ...                             │
│                                     │
│  [加载更多]                          │
└─────────────────────────────────────┘
```

#### 主题分组的生成

切片在 ES 中已有 embedding，利用现有数据动态聚类：

1. 根据 taskId 从 ES 查出该文档全部切片的 embedding
2. K-Means 聚类（k = min(8, chunkCount/5)，即平均每组 ≥5 个切片）
3. 每组用 LLM 生成一个主题标题（输入：组内前 3 个切片的文本，输出：≤10 字标题）
4. 聚类结果缓存到 Redis（key: `chunk-cluster:{taskId}`，TTL: 24h）
5. 每组内按 chunkIndex 排序

**不选固定分组的理由**：文档类型差异大（论文、API 文档、教程），按语义聚类比按原文章节切分更准确。

### 10.3 切片 ↔ 知识卡片双向关联

在"原始知识"（切片）和"结构化知识"（知识卡片）之间建立双向导航，基于现有 embedding 动态计算，无需额外存储。

#### 方向 A：切片 → 关联知识卡片

切片列表中每个切片显示"🔗 N 张关联卡片"。点击展开与该切片语义最相近的已确认知识卡片。

**实现**：用切片的 embedding 在 ES `fish-knowledge-card` 索引做向量检索（top 3），相似度 > 0.7 的展示为关联卡片。点击卡片标题可跳转到知识卡片详情。

**特性**：动态计算——即使后续新增了知识卡片，切片的"关联卡片"也会自动更新。

#### 方向 B：知识卡片 → 源文档切片

在知识卡片详情面板（CardDetailPanel）中，如果 `source_type = knowledge`，显示"📎 源文档切片"区域：

```
├─────────────────────────────────┤
│  📎 源文档切片                     │
│                                 │
│  来自：JVM详解.pdf                │
│  ┌─ 切片 #12  JVM 内存分为五个... │
│  │  相似度：0.91  → [查看切片]     │
│  ├─ 切片 #14  方法区用于存储...    │
│  │  相似度：0.84  → [查看切片]     │
│  └─ [查看全部切片]               │
└─────────────────────────────────┘
```

**实现**：用卡片的 embedding 在源文档的切片中做向量检索（top 5），展示最相关的切片。点击"查看切片"跳转到 KnowledgeView 的切片面板并定位到该切片。

#### 方向 C（进阶，MVP 后）：提取时直接映射

当 AI 从对话中提取卡片，而该对话中 AI 的回答引用了知识库切片（RAG recall 命中过），可以在 RAG tracing 中记录命中关系。后续可根据 trace 数据自动将卡片与切片建立映射。MVP 阶段不做。

### 10.4 后端 API

#### 切片查询

| 方法 | 路径 | 说明 | 响应 |
|------|------|------|------|
| GET | `/api/knowledge/documents/{taskId}/chunks` | 切片列表（支持按分组/搜索） | `ChunkListVO` |
| GET | `/api/knowledge/documents/{taskId}/chunks/groups` | 切片主题分组 | `ChunkGroupVO` |

Query 参数（chunks）：`page, size, keyword, groupIndex`（可选，按分组筛选）

**ChunkGroupVO**：
```json
{
  "taskId": "task-xxx",
  "fileName": "JVM详解.pdf",
  "summary": "本文档涵盖了 JVM 架构、内存模型、GC 机制...",
  "totalChunks": 47,
  "groups": [
    { "groupIndex": 0, "title": "JVM 基础架构", "chunkCount": 8 },
    { "groupIndex": 1, "title": "内存模型与区域划分", "chunkCount": 12 },
    { "groupIndex": 2, "title": "垃圾回收机制", "chunkCount": 11 },
    { "groupIndex": 3, "title": "类加载机制", "chunkCount": 9 },
    { "groupIndex": 4, "title": "JVM 调优参数", "chunkCount": 7 }
  ]
}
```

**ChunkListVO**：
```json
{
  "taskId": "task-xxx",
  "chunks": [
    {
      "chunkIndex": 12,
      "content": "JVM 内存分为五个区域：堆、栈...",
      "charCount": 342,
      "relatedCardCount": 2
    }
  ],
  "total": 12
}
```

#### 切片关联卡片

| 方法 | 路径 | 说明 | 响应 |
|------|------|------|------|
| GET | `/api/knowledge/chunks/{taskId}/{chunkIndex}/related-cards` | 切片关联的知识卡片 | `RelatedCardVO[]` |

**RelatedCardVO**：
```json
[
  { "cardId": 5, "title": "JVM 内存模型", "cardType": "topic", "similarity": 0.91 },
  { "cardId": 12, "title": "堆内存分配", "cardType": "concept", "similarity": 0.78 }
]
```

#### 卡片关联切片（复用现有卡片 API 扩展）

`GET /api/card/{id}` 的 CardVO 中，当 `sourceType = knowledge` 时，额外返回 `relatedChunks` 字段：

```json
{
  "id": 1,
  "...": "...",
  "relatedChunks": [
    {
      "taskId": "task-xxx",
      "fileName": "JVM详解.pdf",
      "chunkIndex": 12,
      "contentPreview": "JVM 内存分为五个区域...",
      "similarity": 0.91
    }
  ]
}
```

### 10.5 后端实现

在现有 `KnowledgeController` 中新增切片相关接口。实现逻辑：

1. **分组接口**：根据 taskId 查 Redis 缓存 → 未命中则从 ES 取该文档全部切片 embedding → K-Means 聚类 → LLM 生成组标题 → 缓存 → 返回
2. **切片列表**：根据 taskId + 可选 groupIndex 在 ES 查询切片，分页返回
3. **切片关联卡片**：取切片 embedding → 在 `fish-knowledge-card` 索引做向量检索 → 返回 top 3
4. **卡片关联切片**：取卡片 embedding → 在知识库索引按 taskId 过滤做向量检索 → 返回 top 5

无需新增 entity/mapper，复用现有 ES 操作 + Redis 缓存。

### 10.6 前端实现

**新增文件**：

| 文件 | 职责 |
|------|------|
| `src/components/ChunkDetailPanel.vue` | 切片可视化侧面板（文档概览 + 主题分组 + 展开切片列表 + 关联卡片） |
| `src/components/ChunkCardLinks.vue` | 切片关联卡片列表（可复用于切片面板和卡片详情面板） |

**修改文件**：

| 文件 | 变更 |
|------|------|
| `src/views/KnowledgeView.vue` | 表格新增「查看切片」按钮列，挂载 ChunkDetailPanel |
| `src/components/CardDetailPanel.vue` | source_type=knowledge 时展示"📎 源文档切片"区域 |
| `src/api/knowledge.ts` | 新增 `getChunkGroups`、`getDocumentChunks`、`getChunkRelatedCards` |

---

_设计文档 · 知识卡片 · v4.1 · 2026-06-06_
