# 卡片分组层级化设计

> 日期：2026-06-07
> 状态：已批准
> 前置：知识卡片系统阶段 1-3.1 已完成

---

## 背景与问题

当前 `knowledge_card.group_name` 是扁平 VARCHAR 字段，LLM 提取时自由生成分组名，导致：
- "算法基础"和"动态规划"各自成为独立平级分组，但逻辑上后者是前者的子集
- 没有归一化去重，LLM 可能同时产出"算法"和"算法基础"两个分组
- 无法表达分组间的层级关系

## 设计决策

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 分组存储 | 新建 `card_group` 实体表 | 与 `keyword` 表同构，归一化 + 层级 + 可查询 |
| 层级深度 | 不限（`parent_id` 自引用） | 用户可能需要多层嵌套，如"计算机科学 > 算法 > 动态规划 > 背包问题" |
| 前端展示 | 树形折叠 Tab（子分组向右展开） | 兼顾层级展示和操作效率 |
| 分组管理 | 无独立管理页面 | 分组在卡片创建/编辑时隐式创建，通过 cascader 选择或新建 |
| 过渡策略 | `group_name` + `group_id` 双写 | 迁移期间兼容旧查询，迁移完成后移除 `group_name` |

---

## Section 1：数据模型

### 新建 `card_group` 表

```sql
CREATE TABLE IF NOT EXISTS card_group (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id         BIGINT       NOT NULL COMMENT '所属用户',
  name            VARCHAR(100) NOT NULL COMMENT '分组显示名，如"动态规划"',
  normalized_name VARCHAR(100) NOT NULL COMMENT '归一化名称（小写+去空格），用于去重匹配',
  parent_id       BIGINT                COMMENT '父分组 ID，NULL 表示顶层分组',
  card_count      INT          NOT NULL DEFAULT 0 COMMENT '直接归属的卡片数（不含子分组）',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE INDEX uk_user_normalized (user_id, normalized_name),
  INDEX idx_user_parent (user_id, parent_id),
  CONSTRAINT fk_group_parent FOREIGN KEY (parent_id) REFERENCES card_group(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='卡片分组实体表（树形层级）';
```

### 改造 `knowledge_card` 表

新增 `group_id` 外键列，`group_name` 保留为遗留字段：

```sql
ALTER TABLE knowledge_card
  ADD COLUMN group_id BIGINT COMMENT '关联 card_group.id' AFTER group_name,
  ADD INDEX idx_user_group_id (user_id, group_id),
  ADD CONSTRAINT fk_card_group FOREIGN KEY (group_id) REFERENCES card_group(id) ON DELETE SET NULL;
```

**约束规则**：
- `card_group.parent_id` 不做深度校验，应用层不限层级
- 删除分组时 `ON DELETE SET NULL`：其下卡片变"未分组"，子分组的 `parent_id` 置 NULL 变顶层
- `card_count` 只统计直接归属的卡片（不含子分组），由 service 层维护

---

## Section 2：后端架构

### 新增 `CardGroupService`（与 `KeywordService` 同构）

```
CardGroupService
├── findOrCreate(userId, name, parentId)       // 归一化 + 幂等创建
├── syncGroupForCard(cardId, userId, groupName) // 写入 group_id + card_count++
├── removeGroupForCard(cardId)                 // 清理 group_id + card_count--
├── getUserGroupTree(userId)                   // 返回分组树
├── getGroupPath(groupId)                      // 返回某分组到根的路径
└── migrateExistingGroups(userId)              // group_name → card_group 迁移
```

核心逻辑：
- `findOrCreate`：`normalized_name = groupName.trim().toLowerCase()`，先查已有 → 有则返回 → 无则 insert（DuplicateKey 兜底）
- `syncGroupForCard`：传了 groupName 则 findOrCreate 拿 groupId 写入 `group_id` + `card_count++`；groupName 为空则 `group_id` 置 NULL
- `getUserGroupTree`：一次查出用户所有 `card_group` 行，应用层按 parentId 组装成树

### 双写策略

过渡期内 `group_name` + `group_id` 同时写入：

| 操作 | group_name | group_id |
|------|-----------|----------|
| 创建/编辑卡片 | 写入（兼容旧查询） | 写入 |
| AI 提取 | 写入 | 写入 |
| 迁移完成后 | 只写 group_id | 写入 |

### 新增文件

| 文件 | 职责 |
|------|------|
| `card/entity/CardGroup.java` | 分组实体（id, userId, name, normalizedName, parentId, cardCount） |
| `card/mapper/CardGroupMapper.java` | 基础 CRUD + 按用户查询 + card_count 更新 |
| `card/service/CardGroupService.java` | 归一化、同步、树组装、迁移 |

### 改动文件

| 文件 | 改动 |
|------|------|
| `KnowledgeCard.java` | 新增 `groupId` 字段 |
| `KnowledgeCardService.java` | create/update/delete 中调用 `CardGroupService.syncGroupForCard` / `removeGroupForCard` |
| `CardExtractService.java` | insertPendingCards 中调用 `CardGroupService.syncGroupForCard` |
| `CardExtractPromptBuilder.java` | 注入分组树结构（不再是扁平列表），让 LLM 知道层级关系 |
| `KnowledgeCardController.java` | 新增 `GET /api/card/groups` 和 `POST /api/card/migrate-groups` |
| `KnowledgeCardMapper.java` | 列表查询增加 `group_id` 关联条件 |
| `CardStatsVO.java` | `GroupStatVO` 增加 `id` 和 `children` 字段，返回树形统计 |
| `CardCreateRequest.java` / `CardUpdateRequest.java` | 新增 `groupId` 可选参数 |

### 新增 REST 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/card/groups` | 返回当前用户的分组树（树形 JSON） |
| POST | `/api/card/migrate-groups` | 将 group_name → card_group 迁移 + 回填 group_id |

---

## Section 3：前端改动

### 树形折叠 Tab（`KnowledgeCardView.vue`）

替换当前扁平 `el-tabs`，实现层级分组展示：

- 顶层分组是一级 Tab，点击筛选该分组 + 所有子分组下的卡片
- 带子分组的 Tab 显示展开箭头，点击弹出子分组下拉面板
- 子分组点击后只筛选该子分组下的卡片
- 分组数据从 `GET /api/card/groups` 获取，返回树形结构，每个节点带 `id / name / cardCount / children[]`

### 级联选择器（`CardCreateDialog.vue`）

分组输入改为 `el-cascader` 级联选择器：
- 展示分组树，支持选择任意层级节点
- 支持直接输入新分组名（filterable + allow-create）
- 提交时传 `groupId`（选了已有分组）或 `groupName`（新建分组）

### 面包屑路径（`CardDetailPanel.vue`）

分组显示改为面包屑层级路径，如"算法基础 > 动态规划 > 背包问题"。

### 图谱过滤（`CardGraphView.vue`）

`groupName` prop 改为 `groupId`，后端递归查所有子分组 id 来过滤。

### 样式适配

所有新增 UI 组件（cascader、树形 Tab、下拉面板）使用项目已有的 CSS 变量（`--bg-glass`、`--border`、`--text-primary`、`--gradient-brand` 等）+ `useTheme()` composable，确保明暗模式一致。

### 改动文件

| 文件 | 改动 |
|------|------|
| `KnowledgeCardView.vue` | el-tabs 改为树形折叠 Tab，分组数据改用 groups API |
| `CardCreateDialog.vue` | 分组输入改为 cascader 级联选择 |
| `CardDetailPanel.vue` | 分组显示改为带面包屑的层级路径 |
| `CardGraphView.vue` | groupName prop 改为 groupId |
| `api/card.ts` | 新增 `getCardGroups()`，`CardStats` 类型中 groups 改为树形结构 |

### 不变的文件

`CardGrid.vue`、`CardExtractPreview.vue`、`CardRelationDiscovery.vue`、`EmptyCardGuide.vue` — 不直接涉及分组逻辑，无需改动。

---

## Section 4：AI 提取 Prompt 改造 & 数据迁移

### Prompt 注入改为树形

当前注入扁平列表：
```
10. 以下是用户已有分组，group_name 优先使用已有分组名：
算法基础、动态规划、数据结构、Java 基础
```

改为注入树形结构：
```
10. 以下是用户已有分组树（缩进表示层级），group_name 优先使用已有分组名：
- 算法基础
  - 动态规划
  - 排序算法
  - 贪心策略
- 数据结构
  - 树
  - 图
- Java 基础
```

同时在 prompt 中增加规则：
> 如果新卡片属于某个已有分组的子领域，优先在该分组下创建子分组（group_name 用 "父分组/子分组" 格式），而不是创建新的平级分组。

### 数据迁移

```
migrateExistingGroups(userId)
  ├─ 1. SELECT DISTINCT group_name FROM knowledge_card WHERE user_id=? AND group_name IS NOT NULL
  ├─ 2. 对每个 group_name → findOrCreate(userId, name, parentId=null)
  ├─ 3. UPDATE knowledge_card SET group_id=? WHERE user_id=? AND group_name=?
  └─ 4. 更新 card_group.card_count
```

通过 `POST /api/card/migrate-groups` 暴露，与 `migrate-keywords` 同模式。

---

_设计文档 · 2026-06-07_
