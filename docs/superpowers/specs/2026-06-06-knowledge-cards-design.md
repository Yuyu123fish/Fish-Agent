# 知识卡片（Knowledge Cards）功能设计

> 日期：2026-06-06
> 版本：v1.0
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
- AI 自动提取（对话结束后用户主动触发，带即时预览）
- 用户手动创建
- 局部关联展示 + vis-network 全局图谱可视化
- 批量确认/拒绝、卡片合并、卡片分组
- 复习模式（纯前端）
- RAG recall 扩展检索知识卡片索引
- 空状态引导、AI 提取建议提示

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
│    ├─ 顶部统计概览条                                      │
│    ├─ 操作栏：搜索 / 筛选 / 分组Tab / 视图切换 / 手动创建   │
│    ├─ 卡片视图：3列网格                                    │
│    ├─ 图谱视图：vis-network 力导向图                       │
│    ├─ 复习模式：逐张翻转复习                               │
│    ├─ CardDetailPanel（右侧滑出详情）                      │
│    └─ CardCreateDialog（创建/编辑对话框）                   │
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
│    └─ POST   /api/card/merge                合并卡片     │
│                                                         │
│  KnowledgeCardService                                   │
│    ├─ CRUD + 确认/拒绝/批量操作/合并                       │
│    └─ MySQL 写入 + ES 同步                               │
│                                                         │
│  CardExtractService                                     │
│    ├─ extractFromSession()                              │
│    │   ├─ 加载对话历史 → 拼 prompt → 调 LLM              │
│    │   ├─ 解析 JSON → 写 MySQL（status=pending）          │
│    │   ├─ 建内部关联（同批次卡片间）                       │
│    │   ├─ 建外部关联（embedding → ES 向量检索 → 匹配已有卡片）│
│    │   └─ 返回提取结果                                    │
│    └─ 生成 embedding（复用 DashScope text-embedding-v2）  │
│                                                         │
│  UserKnowledgeCardSearcher（新增，扩展 RAG recall）        │
│    └─ 并行检索 fish-knowledge-card 索引                   │
└──────────────┬──────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────┐
│                    存储层                                 │
│                                                         │
│  MySQL                                                   │
│    ├─ knowledge_card（卡片主表）                           │
│    └─ card_relation（关联表）                              │
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
| `group_name` | VARCHAR(100) | 分组名称，NULL 表示未分组 |
| `created_at` | DATETIME | 创建时间 |
| `updated_at` | DATETIME | 更新时间 |

索引：
- `idx_user_status` (user_id, status)
- `idx_source` (source_type, source_id)
- `idx_user_group` (user_id, group_name)

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
- `contains` — 包含（A 包含 B）
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

写入时机：仅 `status = confirmed` 的卡片写入/更新。pending/rejected 不进索引。卡片被拒绝或删除时从 ES 移除。

---

## 4. AI 提取引擎

### 4.1 触发方式

**主路径**：用户在 ChatView 点击「提取知识卡片」按钮，传入 sessionId。

**被动提示**（改进项 E）：对话满足以下条件时，聊天区底部显示非阻塞提示：
- 当前会话 ≥ 8 轮
- 对话中包含知识性关键词（"概念"、"原理"、"流程"、"区别"、"机制"、"架构"等）

提示样式：`💡 这段对话包含多个知识点，点击提取为知识卡片`

### 4.2 LLM Prompt

**输入**：该 session 的完整对话历史（user + assistant 消息，跳过 tool 类型）

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

### 4.3 后端处理流程

```
extractFromSession(sessionId, userId)
  │
  ├─ 1. 从 chat_history 加载该 session 全部对话
  │
  ├─ 2. 拼接 prompt + 对话原文 → 调 LLM（复用现有 llm 模块）
  │
  ├─ 3. 解析 LLM 返回的 JSON
  │     └─ 校验：title 非空、content 非空、relation 的 from_title/to_title 存在
  │
  ├─ 4. 批量写入 knowledge_card 表（status=pending）
  │     └─ 记录 title → cardId 映射
  │
  ├─ 5. 建立内部关联（同批次卡片间的 relations）
  │     └─ from_title/to_title → 查映射得 cardId → 写 card_relation
  │
  ├─ 6. 建立外部关联（与用户已有 confirmed 卡片的关联）
  │     ├─ 对每张新卡片生成 embedding（复用 DashScope text-embedding-v2）
  │     ├─ 用 embedding 在 ES fish-knowledge-card 做向量检索（top 5）
  │     ├─ 相似度 > 0.75 的已有卡片 → 自动建 related_to 关联
  │     └─ 写 card_relation，confidence = similarity score
  │
  └─ 7. 返回提取结果 { extractedCount, cardIds[], cards[] }
        └─ cards[] 包含每张卡片的完整数据，供前端即时预览
```

### 4.4 错误处理

- LLM 返回非 JSON → 记录日志，返回错误"提取失败，请稍后重试"
- JSON 解析成功但部分卡片校验失败 → 跳过无效卡片，保留有效的
- ES 向量检索失败 → 跳过外部关联步骤，不影响主流程

---

## 5. REST API

### 5.1 卡片 CRUD

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/api/card/extract/{sessionId}` | AI 提取 | — | `{extractedCount, cardIds[], cards[]}` |
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
| POST | `/api/card/merge` | 合并卡片 | `{keepId, discardId}` | — |

合并逻辑：保留 keepId 的标题/内容，合并双方 keywords（去重），discardId 的所有关联转移到 keepId，删除 discardId 及其关联。

### 5.4 统计接口

| 方法 | 路径 | 说明 | 响应 |
|------|------|------|------|
| GET | `/api/card/stats` | 卡片统计 | `CardStatsVO` |

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

## 6. 后端包结构

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

### 6.1 RAG 扩展

新增 `UserKnowledgeCardSearcher` 于 `rag/pipeline/recall/` 下：

```
rag/pipeline/recall/
├── ...（现有文件不变）
└── UserKnowledgeCardSearcher.java     // 检索 fish-knowledge-card 索引
```

- 逻辑与 `UserKnowledgeElasticsearchSearcher` 类似
- 在 `RagRecall` 中作为并行检索源之一
- 检索范围：当前用户的 confirmed 卡片
- 结果参与后续 rerank + fusion 流程

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
| `src/views/KnowledgeCardView.vue` | 知识卡片主页面（统计栏 + 操作栏 + 卡片/图谱双视图 + 空状态引导 + 复习模式入口） |
| `src/components/CardGrid.vue` | 卡片网格视图（3列响应式网格 + 卡片卡片 + 批量操作） |
| `src/components/CardDetailPanel.vue` | 右侧滑出详情面板（420px + 关联列表 + 疑似重复提示 + 合并） |
| `src/components/CardCreateDialog.vue` | 手动创建/编辑对话框（标题 + Markdown textarea + 关键词输入 + 类型选择 + 分组选择） |
| `src/components/CardGraphView.vue` | vis-network 图谱可视化（力导向布局 + 节点交互 + 关系筛选） |
| `src/components/CardReviewMode.vue` | 复习模式（逐张翻转 + 忘了/模糊/熟悉） |
| `src/components/CardExtractPreview.vue` | 提取即时预览面板（勾选 + 编辑 + 确认） |
| `src/components/EmptyCardGuide.vue` | 空状态引导组件（双卡片入口 + 使用贴士） |
| `src/api/card.ts` | 卡片 API 调用 |
| `src/composables/useCardDetail.ts` | 详情面板开关状态（类似 useDrawer） |

### 7.3 修改文件

| 文件 | 变更 |
|------|------|
| `src/router/index.ts` | 新增 `/cards` 路由 |
| `src/components/AppHeader.vue` | 新增 📋 知识卡片导航按钮 |
| `src/components/DrawerSidebar.vue` | 侧栏菜单新增知识卡片入口 |
| `src/views/ChatView.vue` | 输入区新增「提取知识卡片」按钮 + 被动提示逻辑 |

### 7.4 页面布局

#### KnowledgeCardView 主页面

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
- 疑似重复：confidence > 0.9 的关联标记为"疑似重复"，提供「合并」按钮

#### 图谱视图

使用 vis-network 实现力导向图：

- **节点样式**：
  - 概念卡片：小圆圈，40px，填充 `var(--primary-dim)` `#4f46e5`
  - 主题卡片：大圆圈，60px，填充渐变 `var(--gradient-brand)`
  - 选中节点：外圈发光 `var(--glow-primary)`
  - 节点内显示标题（字数过多截断）
  - 悬浮显示完整标题 + 内容摘要 tooltip

- **连线样式**（按关系类型区分）：
  - `related_to` → 灰色虚线
  - `contains` → 蓝色实线
  - `precedes` → 紫色实线 + 箭头
  - `derived_from` → 绿色实线 + 箭头

- **交互**：
  - 单击节点 → 弹出浮层（标题 + 摘要 + 跳转详情按钮）
  - 双击节点 → 打开右侧详情面板
  - 拖拽节点 → 力导向自动重布局
  - 滚轮缩放 + 拖拽画布平移
  - 筛选：按关系类型过滤连线
  - 全屏按钮：画布撑满页面

- **数据加载**：前端调 `GET /api/card/list`（status=confirmed，不分页取全部）+ 关联数据，前端组装为 vis-network nodes + edges。MVP 阶段前端全量加载（个人卡片量级在几十到几百）。

#### 复习模式

- 卡片页顶部 🎯「复习」按钮
- 逐张展示：正面显示标题 → 用户点击翻转显示内容
- 每张底部三个按钮：`忘了` / `模糊` / `熟悉`
- 简单轮次逻辑：标记"忘了"的会在本轮后面重新出现，"熟悉"的跳过
- MVP 纯前端逻辑，不做持久化复习记录

#### 提取即时预览（改进项 C）

提取 API 返回后，前端弹出预览面板：

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

- 默认全选，用户可取消勾选
- 点击编辑可当场修改标题/内容/关键词
- 全部确认后卡片直接变为 confirmed（跳过 pending），同步写 ES
- 取消 → 未勾选的留在 pending，已勾选的也留在 pending

#### 空状态引导（改进项 B）

无卡片时展示引导界面：

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

#### AI 提取建议（改进项 E）

对话满足条件时，ChatView 聊天区底部显示非阻塞提示：

```
💡 这段对话包含多个知识点，点击提取为知识卡片
```

条件：会话 ≥ 8 轮 + 包含知识性关键词。点击后走正常提取流程。不点则消失。

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
| 图谱实现 | 渐进式（先局部关联，后全局图谱） | MVP 可交付，降低首版复杂度 |
| 存储方案 | MySQL + ES 双写 | 与现有 RAG 架构一致 |
| 提取触发 | 用户主动 + 被动提示 | 平衡控制感和可发现性 |
| 提取后流程 | 即时预览 → 确认 | 趁热打铁，减少后续确认摩擦 |
| 卡片分组 | group_name 字段（非新表） | 轻量，MVP 够用 |
| RAG 联动 | confirmed 卡片参与 RAG recall | 闭环：卡片 → AI 回答 → 新卡片 |
| 图谱库 | vis-network | 力导向开箱即用，与暗色主题搭 |
| 复习模式 | 纯前端一轮过 | MVP 轻量，后续可加间隔重复 |

---

_设计文档 · 知识卡片 · v1.0 · 2026-06-06_
