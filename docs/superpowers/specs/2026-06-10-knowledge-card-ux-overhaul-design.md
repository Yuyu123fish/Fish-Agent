# 知识卡片体验全面重构设计

> 日期：2026-06-10
> 状态：待实现
> 范围：前端 + 后端（适度重构）

---

## 1. 背景与目标

当前知识卡片系统已具备基础 CRUD、分组管理、图谱视图、AI 提取等功能，但从用户交互角度存在以下痛点：

- **浏览和查找**：卡片网格信息密度低，搜索能力有限，分组筛选不够灵活
- **复习体验**：前端临时队列，无间隔重复调度，无复习统计
- **创建和管理**：入口单一，批量操作流程繁琐，卡片整理效率低
- **整体交互感**：工具栏拥挤，布局不够现代，缺少快捷操作

**目标**：将知识卡片从"数据管理页面"升级为"知识学习工具"。

---

## 2. 整体布局：三栏式

```
┌──────────────────────────────────────────────────────────────┐
│  AppHeader（返回 + 标题）                                       │
├──────────┬───────────────────────────────────┬───────────────┤
│          │  统计卡片条（水平排列）                │               │
│  左侧栏  │────────────────────────────────── │   右侧面板     │
│  分组树   │  工具栏（搜索 + 筛选 + 操作）       │   卡片详情     │
│          │────────────────────────────────── │   (点击打开)   │
│  · 全部  │  卡片网格（可展开卡片）              │               │
│  · 分组1 │                                   │               │
│  · 分组2 │  ┌─────┐ ┌─────┐ ┌─────┐          │               │
│  · ...   │  │卡片1 │ │卡片2 │ │卡片3 │          │               │
│          │  └─────┘ └─────┘ └─────┘          │               │
│          │                                   │               │
│          │  分页                               │               │
├──────────┴───────────────────────────────────┴───────────────┤
│  批量操作浮动条（选中时出现）                                     │
└──────────────────────────────────────────────────────────────┘
```

### 2.1 左栏（220px，可折叠到 0）

- 顶部：分组树标题 + 折叠按钮（`<<` 图标）
- 搜索框：快速筛选分组名称
- 树形分组列表，每项显示名称 + 卡片数量
- 支持展开/折叠子分组
- 底部：快速操作（新建分组、发现关联）
- 宽度可拖拽调整（180–280px 范围）

### 2.2 中栏（弹性宽度）

- 统计卡片条：总卡片、已确认、待确认、到期复习、本周新增（5 个指标）
- 两层工具栏（见第 5 节）
- 可展开卡片网格（见第 3 节）
- 分页控件

### 2.3 右栏（420px，点击卡片时滑入）

- 保持现有侧滑面板交互
- 详情面板增强（见第 6 节）
- 新增复习状态信息

### 2.4 响应式规则

| 断点 | 左栏 | 中栏 | 右栏 |
|------|------|------|------|
| ≥ 1200px | 220px（可拖拽 180–280px） | 弹性 | 420px 侧滑 |
| 768–1199px | 折叠为图标触发 | 弹性 | 420px 侧滑 |
| < 768px | 隐藏（抽屉触发） | 100% | 100% 全屏覆盖 |

---

## 3. 可展开卡片设计

### 3.1 默认紧凑态

```
┌──────────────────────────────┐
│ ☐  ● 标题文字               主题│
│                              │
│ 内容前80字预览...              │
│                              │
│ 🏷 关键词1  关键词2  关键词3    │
│ 📁 分组名    🔗 3 关联  📅 3天前│
│ 🔄 下次复习：明天              │
└──────────────────────────────┘
```

新增字段（相对现有）：
- 创建时间相对显示（3天前、1周前）
- 复习状态指示（下次复习到期时间，颜色标识紧急程度）

### 3.2 展开态

```
┌──────────────────────────────────────┐
│ ☐  ● 标题文字                       主题│
│                                      │
│ 内容展开到 300 字（Markdown 渲染）...    │
│                                      │
│ 🏷 关键词1  关键词2  关键词3  +2 更多   │
│ 📁 路径 > 分组名                       │
│ 📊 复习 3 次 | 上次：2天前 | 下次：明天    │
│ 🔗 关联：卡片A、卡片B（+1 更多）         │
│                                      │
│ [确认] [编辑] [拒绝] [删除]             │
└──────────────────────────────────────┘
```

新增信息：
- Markdown 内容预览（截断到 300 字）
- 关联卡片列表（可点击跳转）
- 快捷操作按钮
- 完整复习信息

### 3.3 交互规则

- 点击卡片体 → 展开卡片（手风琴模式，同时只展开一张）
- 展开状态下点击标题 → 打开右侧详情面板
- 复选框在卡片左上角，展开/折叠都可见
- 待确认卡片左边框橙色，已确认绿色（保持现有）
- 复习到期卡片加微妙脉冲动画提示

---

## 4. SM-2 间隔重复复习系统

### 4.1 数据模型

**新增表 `card_review_record`**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | 主键 |
| card_id | BIGINT NOT NULL | 关联卡片 |
| user_id | BIGINT NOT NULL | 用户 ID |
| quality | TINYINT NOT NULL | 评分 0–5 |
| easiness_factor | FLOAT NOT NULL DEFAULT 2.5 | 难度因子（SM-2） |
| interval | INT NOT NULL DEFAULT 0 | 当前间隔天数 |
| repetition | INT NOT NULL DEFAULT 0 | 连续正确次数 |
| reviewed_at | DATETIME NOT NULL | 本次复习时间 |
| next_review_at | DATETIME NOT NULL | 下次应复习时间 |
| created_at | DATETIME DEFAULT NOW() | 记录创建时间 |

索引：
- `idx_card_user (card_id, user_id)`
- `idx_user_next (user_id, next_review_at)`
- `idx_card_user_reviewed (user_id, card_id, reviewed_at DESC)`

**知识卡片表 `knowledge_card` 新增字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| review_next_at | DATETIME NULL | 下次复习到期时间（冗余，方便列表查询） |
| review_count | INT DEFAULT 0 | 累计复习次数 |
| last_reviewed_at | DATETIME NULL | 上次复习时间 |

### 4.2 SM-2 算法

用户评分映射：

| 按钮 | quality 值 | 含义 |
|------|-----------|------|
| 忘了 | 0 | 完全不记得 |
| 模糊 | 3 | 有印象但不确定 |
| 熟悉 | 5 | 轻松回忆 |

核心调度逻辑：

```
输入：quality (0/3/5), 当前 easiness_factor, interval, repetition

if quality >= 3:
    if repetition == 0: interval = 1
    elif repetition == 1: interval = 6
    else: interval = round(interval * easiness_factor)
    repetition += 1
else:
    repetition = 0
    interval = 1

easiness_factor = max(1.3, easiness_factor + 0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02))
next_review_at = now + interval 天
```

### 4.3 新增 API

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/card/review/queue` | GET | 获取到期复习卡片队列，参数：`groupId`（可选） |
| `/api/card/review/answer` | POST | 提交复习评分，参数：`{ cardId, quality }` |
| `/api/card/review/stats` | GET | 获取复习统计数据 |

**`GET /api/card/review/queue` 响应**：

```json
{
  "cards": [
    {
      "id": 1,
      "title": "...",
      "content": "...",
      "keywords": [],
      "cardType": "concept",
      "groupPath": "父 > 子",
      "reviewInfo": {
        "interval": 6,
        "repetition": 3,
        "easinessFactor": 2.5,
        "lastReviewedAt": "2026-06-07T10:00:00",
        "nextReviewAt": "2026-06-13T10:00:00"
      }
    }
  ],
  "totalDue": 5,
  "totalNew": 2
}
```

**`POST /api/card/review/answer` 请求/响应**：

```json
// 请求
{ "cardId": 1, "quality": 5 }

// 响应
{
  "nextReviewAt": "2026-06-17T10:00:00",
  "interval": 6,
  "easinessFactor": 2.6,
  "remainingDue": 4
}
```

**`GET /api/card/review/stats` 响应**：

```json
{
  "totalCards": 50,
  "mastered": 20,    // repetition >= 3 且 easinessFactor >= 2.0
  "learning": 15,    // 有复习记录但未达标
  "dueToday": 5,
  "streakDays": 7,
  "reviewCalendar": {
    "2026-06-09": 8,
    "2026-06-10": 5
  },
  "weeklyActivity": [0, 5, 3, 8, 2, 0, 0]  // 周一到周日
}
```

### 4.4 修改现有 API

**`GET /api/card/list`** — 返回字段新增：
- `reviewNextAt`: DateTime | null
- `reviewCount`: int

（向后兼容，不传新参数不影响现有行为）

**`GET /api/card/{id}`** — 返回字段新增 `reviewInfo` 对象：
```json
{
  "reviewInfo": {
    "nextReviewAt": "2026-06-13T10:00:00",
    "reviewCount": 5,
    "lastReviewedAt": "2026-06-07T10:00:00",
    "easinessFactor": 2.5,
    "interval": 6,
    "repetition": 3
  }
}
```

### 4.5 复习统计面板

在复习模式完成后展示或可通过工具栏查看：

- **掌握度分布**：饼图显示已掌握 / 学习中 / 到期未复习
- **复习日历**：近 30 天热力图，每天复习了几张
- **连续学习天数**：当前 streak
- **今日待复习**：数字提示

### 4.6 复习模式 UI 改进

- 保留翻转卡片交互
- 评分按钮：忘了（红）/ 模糊（黄）/ 熟悉（绿）
- 翻转背面底部：下次复习间隔预览（"下次复习：3天后"）
- 进度条：已复习 X / 到期 Y 张
- 完成后显示本轮统计（正确率、平均评分）

---

## 5. 搜索增强 + 工具栏重构

### 5.1 工具栏两行布局

**第一行 — 主操作区**：
```
[🔍 搜索标题或内容...]  [+ 创建卡片]  [☐ 卡片 | ◉ 图谱]  [🔄 复习 (3)]
```

- 搜索框：宽度自适应，回车搜索，清除触发
- 创建按钮：主要操作，accent 色突出
- 视图切换：卡片 / 图谱（保持现有）
- 复习按钮：带待复习数量角标

**第二行 — 筛选区**：
```
[状态 ▼]  [类型 ▼]  [排序 ▼]  [📅 复习到期]  [发现关联]
```

- 状态筛选：全部 / 已确认 / 待确认 / 已拒绝
- 类型筛选：全部 / 概念 / 主题
- 排序：默认 / 最近创建 / 最近更新 / 复习到期
- 复习到期筛选：快捷按钮，筛选到期卡片
- 发现关联：保持现有

### 5.2 搜索增强

**前端筛选**（无需新接口）：
- 关键词标签可点击 → 自动填入搜索框并搜索
- 分组树选中 → 按 groupId 筛选（已有）
- 排序选项 → listCards 参数 + 前端排序

**后端 listCards 扩展参数**（向后兼容）：
- `cardType`：按类型筛选（concept / topic）
- `reviewOverdue`：布尔值，筛选复习到期卡片
- `sortBy`：排序字段（createdAt / updatedAt / reviewNextAt）
- `sortOrder`：asc / desc

---

## 6. 详情面板优化

### 6.1 头部区域

```
[概念] 标题文字
来源：来自对话 · 创建于 2026-06-01
```
新增：创建时间显示

### 6.2 新增复习信息区块

```
📚 复习状态
  掌握度：●●●○○ 中等
  已复习：5 次
  上次复习：3 天前
  下次复习：明天（到期）
  间隔：6 天
```

- 掌握度计算：`level = min(5, max(1, round(easinessFactor * repetition / 2)))`，1=初学, 3=中等, 5=精通
- 到期卡片橙色高亮
- 未复习卡片显示"尚未复习"

### 6.3 关联卡片区块增强

- 每张关联卡片显示类型标签和复习状态
- 点击关联卡片 → 在面板中切换（保持现有）

### 6.4 操作区

```
[编辑] [确认] [拒绝] [合并重复] [删除]
```

- "合并重复"只在检测到高置信度关联时显示

### 6.5 响应式

| 断点 | 面板宽度 |
|------|---------|
| ≥ 1200px | 420px 侧滑 |
| 768–1199px | 60% 宽度 |
| < 768px | 100% 全屏覆盖 |

---

## 7. 文件变更清单

### 7.1 后端新增

| 文件 | 说明 |
|------|------|
| `card/entity/CardReviewRecord.java` | 复习记录实体 |
| `card/mapper/CardReviewRecordMapper.java` | Mapper |
| `card/service/CardReviewService.java` | SM-2 调度逻辑 |
| `card/controller/CardReviewController.java` | 复习相关 API |
| `card/dto/ReviewAnswerRequest.java` | 提交评分 DTO |
| `card/dto/ReviewQueueResponse.java` | 复习队列 DTO |
| `card/dto/ReviewStatsResponse.java` | 复习统计 DTO |
| `card/dto/ReviewInfoDTO.java` | 卡片复习信息 DTO |

### 7.2 后端修改

| 文件 | 说明 |
|------|------|
| `card/entity/KnowledgeCard.java` | 新增 reviewNextAt, reviewCount, lastReviewedAt 字段 |
| `card/service/KnowledgeCardService.java` | listCards 返回新增字段，getCard 返回 reviewInfo |
| `card/controller/KnowledgeCardController.java` | listCards 接受新参数（cardType, reviewOverdue, sortBy, sortOrder） |
| `card/dto/CardListItemDTO.java` | 新增 reviewNextAt, reviewCount |
| `card/dto/CardDetailDTO.java` | 新增 reviewInfo 对象 |
| `database/sql/fish_agent.sql` | ALTER TABLE 新增字段 + CREATE TABLE card_review_record |

### 7.3 前端新增

| 文件 | 说明 |
|------|------|
| `components/CardSidebar.vue` | 左侧分组树组件 |
| `components/ReviewStatsPanel.vue` | 复习统计面板 |

### 7.4 前端修改

| 文件 | 说明 |
|------|------|
| `views/KnowledgeCardView.vue` | 三栏布局重构，工具栏重构 |
| `components/CardGrid.vue` | 可展开卡片交互 |
| `components/CardDetailPanel.vue` | 新增复习信息区块，头部改进 |
| `components/CardReviewMode.vue` | SM-2 适配，统计展示，间隔预览 |
| `api/card.ts` | 新增复习相关 API 函数，更新类型定义 |

---

## 8. 实现顺序

1. **数据库变更**：新增 `card_review_record` 表 + `knowledge_card` 新增字段
2. **后端复习系统**：SM-2 服务 + 复习 API 控制器
3. **后端列表接口调整**：listCards 新增筛选/排序参数 + 返回复习字段
4. **前端三栏布局**：CardSidebar 组件 + KnowledgeCardView 布局重构
5. **前端可展开卡片**：CardGrid 组件改造
6. **前端工具栏重构**：两层工具栏 + 新筛选
7. **前端复习模式改造**：适配 SM-2 后端 + 统计面板
8. **前端详情面板优化**：复习信息区块 + 关联增强
9. **联调测试**：端到端验证所有功能
