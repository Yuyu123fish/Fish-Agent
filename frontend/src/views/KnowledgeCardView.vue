<script setup lang="ts">
/**
 * 知识卡片主页面：阶段 3.2 支持树形分组、groupId 筛选。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Link, Refresh, Plus, Search, ArrowRight, View } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppHeader from '@/components/AppHeader.vue'
import DrawerSidebar from '@/components/DrawerSidebar.vue'
import CardGrid from '@/components/CardGrid.vue'
import CardGraphView from '@/components/CardGraphView.vue'
import CardReviewMode from '@/components/CardReviewMode.vue'
import CardRelationDiscovery from '@/components/CardRelationDiscovery.vue'
import CardDetailPanel from '@/components/CardDetailPanel.vue'
import CardCreateDialog from '@/components/CardCreateDialog.vue'
import EmptyCardGuide from '@/components/EmptyCardGuide.vue'
import {
  batchConfirmCards,
  batchRejectCards,
  deleteCard,
  getCardStats,
  getCardGroups,
  listCards,
  type CardDetail,
  type CardListItem,
  type CardStats,
  type GroupTreeNode
} from '@/api/card'

const router = useRouter()
const route = useRoute()

function goChat() {
  void router.push('/chat')
}

const loading = ref(false)
const cards = ref<CardListItem[]>([])
const selectedIds = ref<number[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(12)
const keyword = ref('')
const status = ref<'all' | 'confirmed' | 'pending' | 'rejected'>('all')
const groupName = ref('all')
const groupId = ref<number | null>(null)
const viewMode = ref<'grid' | 'graph'>('grid')
const reviewMode = ref(false)
const groupTree = ref<GroupTreeNode[]>([])
const expandedGroupId = ref<number | null>(null)
const stats = ref<CardStats>({
  total: 0,
  confirmed: 0,
  pending: 0,
  relationCount: 0,
  weekNew: 0,
  groups: []
})

const detailVisible = ref(false)
const selectedCardId = ref<number | null>(null)
const detailRefreshKey = ref(0)
const graphRefreshKey = ref(0)
const dialogVisible = ref(false)
const editingCard = ref<CardDetail | null>(null)
const discoveryVisible = ref(false)

const groupOptions = computed(() => flattenGroupNames(groupTree.value))
const showEmptyGuide = computed(() => !loading.value && stats.value.total === 0)
const visiblePendingIds = computed(() => cards.value.filter((c) => c.status === 'pending').map((c) => c.id))

/** 从树中收集所有分组名（用于 cascader 兼容） */
function flattenGroupNames(nodes: GroupTreeNode[]): string[] {
  const out: string[] = []
  for (const n of nodes) {
    out.push(n.name)
    if (n.children?.length) out.push(...flattenGroupNames(n.children))
  }
  return out
}

/** 切换分组：支持 groupId */
function selectGroup(gId: number | null, gName?: string) {
  if (gId == null) {
    groupId.value = null
    groupName.value = 'all'
  } else {
    groupId.value = gId
    groupName.value = gName ?? 'all'
  }
  resetAndLoad()
}

/** 统计树中所有节点的卡片总数（含子节点） */
function totalCount(node: GroupTreeNode): number {
  let sum = node.cardCount
  if (node.children?.length) {
    for (const c of node.children) sum += totalCount(c)
  }
  return sum
}

async function loadCards() {
  loading.value = true
  try {
    const [statData, pageData, treeData] = await Promise.all([
      getCardStats(),
      listCards({
        page: page.value,
        size: pageSize.value,
        keyword: keyword.value,
        status: status.value,
        groupName: groupId.value ? undefined : groupName.value,
        groupId: groupId.value
      }),
      getCardGroups()
    ])
    stats.value = statData
    cards.value = pageData.records
    total.value = pageData.total
    groupTree.value = treeData
    selectedIds.value = selectedIds.value.filter((id) => pageData.records.some((c) => c.id === id))
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载知识卡片失败')
  } finally {
    loading.value = false
  }
}

function resetAndLoad() {
  if (page.value === 1) void loadCards()
  else page.value = 1
}

function openCreate() {
  editingCard.value = null
  dialogVisible.value = true
}

async function openEdit(card: CardDetail) {
  editingCard.value = card
  dialogVisible.value = true
}

async function selectCard(card: CardListItem) {
  openDetailById(card.id)
}

function openDetailById(id: number) {
  selectedCardId.value = id
  detailVisible.value = true
}

async function handleSaved() {
  dialogVisible.value = false
  editingCard.value = null
  await loadCards()
  if (selectedCardId.value) {
    // 详情面板内部自行拉取详情，刷新 key 用于编辑保存后强制重建面板状态。
    detailRefreshKey.value += 1
  }
}

async function handleDeleted() {
  detailVisible.value = false
  selectedCardId.value = null
  await loadCards()
}

function toggleSelection(card: CardListItem, checked: boolean) {
  selectedIds.value = checked
    ? [...new Set([...selectedIds.value, card.id])]
    : selectedIds.value.filter((id) => id !== card.id)
}

function toggleSelectAll(checked: string | number | boolean) {
  if (checked) {
    selectedIds.value = cards.value.map((c) => c.id)
  } else {
    selectedIds.value = []
  }
}

async function batchConfirm(ids = selectedIds.value) {
  if (ids.length === 0) return
  try {
    await batchConfirmCards(ids)
    ElMessage.success(`已确认 ${ids.length} 张卡片`)
    selectedIds.value = []
    await loadCards()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '批量确认失败')
  }
}

async function batchReject(ids = selectedIds.value) {
  if (ids.length === 0) return
  try {
    await batchRejectCards(ids)
    ElMessage.success(`已拒绝 ${ids.length} 张卡片`)
    selectedIds.value = []
    await loadCards()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '批量拒绝失败')
  }
}

async function batchDelete() {
  const ids = selectedIds.value
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${ids.length} 张卡片？删除后不可恢复。`, '批量删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await Promise.all(ids.map((id) => deleteCard(id)))
    ElMessage.success(`已删除 ${ids.length} 张卡片`)
    selectedIds.value = []
    await loadCards()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '批量删除失败')
  }
}

async function handleRelationsConfirmed() {
  discoveryVisible.value = false
  graphRefreshKey.value += 1
  await loadCards()
}

/** 进入复习时清空批量选择，避免隐藏列表后仍保留悬浮批量操作条。 */
function openReviewMode() {
  selectedIds.value = []
  reviewMode.value = true
}

function closeReviewMode() {
  reviewMode.value = false
}

onMounted(async () => {
  await loadCards()
  openCardFromRoute()
})
watch([page, pageSize], () => void loadCards())
watch(() => route.query.openCard, () => openCardFromRoute())

function openCardFromRoute() {
  const id = typeof route.query.openCard === 'string' ? Number(route.query.openCard) : null
  if (id && Number.isFinite(id)) {
    openDetailById(id)
  }
}
</script>

<template>
  <AppHeader :show-back="true" @back="goChat" />
  <DrawerSidebar />

  <main class="cards-page">
    <section class="cards-shell">
      <header class="page-head">
        <div>
          <h1>知识卡片</h1>
          <p>整理概念、主题和关键经验，让知识可复用、可检索、可连接。</p>
        </div>
        <button class="primary-btn" type="button" @click="openCreate">
          <el-icon><Plus /></el-icon>
          手动创建
        </button>
      </header>

      <section class="stats-strip">
        <div class="stat-item">
          <span>总卡片</span>
          <strong>{{ stats.total }}</strong>
        </div>
        <div class="stat-item">
          <span>已确认</span>
          <strong>{{ stats.confirmed }}</strong>
        </div>
        <div class="stat-item">
          <span>待确认</span>
          <strong>{{ stats.pending }}</strong>
        </div>
        <div class="stat-item">
          <span>关联</span>
          <strong>{{ stats.relationCount }}</strong>
        </div>
        <div class="stat-item">
          <span>近 7 天</span>
          <strong>{{ stats.weekNew }}</strong>
        </div>
      </section>

      <EmptyCardGuide v-if="showEmptyGuide" @create="openCreate" />

      <template v-else>
        <section class="toolbar">
          <el-input
            v-model="keyword"
            class="search"
            clearable
            placeholder="搜索标题或内容"
            :prefix-icon="Search"
            @keyup.enter="resetAndLoad"
            @clear="resetAndLoad"
          />
          <el-select v-model="status" class="status-select" @change="resetAndLoad">
            <el-option label="全部状态" value="all" />
            <el-option label="已确认" value="confirmed" />
            <el-option label="待确认" value="pending" />
            <el-option label="已拒绝" value="rejected" />
          </el-select>
          <button class="ghost-btn" type="button" @click="resetAndLoad">
            <el-icon><Search /></el-icon>
            搜索
          </button>
          <button class="icon-btn" title="刷新" type="button" @click="loadCards">
            <el-icon><Refresh /></el-icon>
          </button>
          <button class="ghost-btn" type="button" :disabled="visiblePendingIds.length === 0" @click="batchConfirm(visiblePendingIds)">
            {{ visiblePendingIds.length > 0 ? `全部确认 (${visiblePendingIds.length})` : '无待确认' }}
          </button>
          <button class="ghost-btn danger-text" type="button" :disabled="visiblePendingIds.length === 0" @click="batchReject(visiblePendingIds)">
            {{ visiblePendingIds.length > 0 ? `全部拒绝 (${visiblePendingIds.length})` : '无待确认' }}
          </button>
          <button class="ghost-btn review-btn" type="button" :class="{ active: reviewMode }" @click="openReviewMode">
            <el-icon><View /></el-icon>
            复习
          </button>
          <div class="view-switch">
            <button
              type="button"
              class="view-btn"
              :class="{ active: viewMode === 'grid' }"
              @click="viewMode = 'grid'"
            >
              ☐ 卡片
            </button>
            <button
              type="button"
              class="view-btn"
              :class="{ active: viewMode === 'graph' }"
              @click="viewMode = 'graph'"
            >
              ◉ 图谱
            </button>
          </div>
          <button class="ghost-btn discover-btn" type="button" @click="discoveryVisible = true">
            <el-icon><Link /></el-icon>
            发现关联
          </button>
        </section>

        <section class="group-tabs">
          <button
            type="button"
            class="group-tab"
            :class="{ active: groupId == null && groupName === 'all' }"
            @click="selectGroup(null)"
          >
            全部分组
          </button>
          <div v-for="node in groupTree" :key="node.id" class="group-tab-wrap">
            <button
              type="button"
              class="group-tab"
              :class="{ active: groupId === node.id }"
              @click="selectGroup(node.id, node.name)"
            >
              {{ node.name }}
              <span class="tab-count">({{ totalCount(node) }})</span>
              <el-icon
                v-if="node.children?.length"
                class="expand-arrow"
                :class="{ expanded: expandedGroupId === node.id }"
                @click.stop="expandedGroupId = expandedGroupId === node.id ? null : node.id"
              >
                <ArrowRight />
              </el-icon>
            </button>
            <Transition name="dropdown">
              <div v-if="node.children?.length && expandedGroupId === node.id" class="sub-group-panel">
                <button
                  v-for="child in node.children"
                  :key="child.id"
                  type="button"
                  class="sub-group-item"
                  :class="{ active: groupId === child.id }"
                  @click="selectGroup(child.id, child.name)"
                >
                  {{ child.name }}
                  <span class="tab-count">({{ child.cardCount }})</span>
                </button>
              </div>
            </Transition>
          </div>
        </section>

        <CardReviewMode
          v-if="reviewMode"
          :group-id="groupId"
          :group-name="groupName"
          @close="closeReviewMode"
        />

        <template v-else>
          <div v-if="viewMode === 'grid' && cards.length > 0" class="select-all-row">
            <el-checkbox
              :model-value="selectedIds.length === cards.length && cards.length > 0"
              :indeterminate="selectedIds.length > 0 && selectedIds.length < cards.length"
              @change="toggleSelectAll"
            >
              全选（{{ cards.length }} 张）
            </el-checkbox>
          </div>

          <div v-if="viewMode === 'grid'" v-loading="loading" class="grid-wrap">
            <CardGrid
              v-if="cards.length > 0"
              :cards="cards"
              :selected-ids="selectedIds"
              @select="selectCard"
              @toggle="toggleSelection"
            />
            <el-empty v-else description="没有匹配的知识卡片" />
          </div>

          <CardGraphView
            v-else
            :group-id="groupId"
            :refresh-key="graphRefreshKey"
            @open-detail="openDetailById"
          />

          <div v-if="viewMode === 'grid'" class="pager">
            <el-pagination
              v-model:current-page="page"
              v-model:page-size="pageSize"
              :page-sizes="[12, 24, 48]"
              :total="total"
              layout="total, sizes, prev, pager, next"
              background
            />
          </div>
        </template>
      </template>
    </section>
  </main>

  <CardDetailPanel
    :key="detailRefreshKey"
    :visible="detailVisible"
    :card-id="selectedCardId"
    @close="detailVisible = false"
    @edit="openEdit"
    @deleted="handleDeleted"
  />

  <CardCreateDialog
    :visible="dialogVisible"
    :edit-card="editingCard"
    :group-tree="groupTree"
    @close="dialogVisible = false"
    @saved="handleSaved"
  />

  <CardRelationDiscovery
    :visible="discoveryVisible"
    @close="discoveryVisible = false"
    @confirmed="handleRelationsConfirmed"
  />

  <Transition name="batch">
    <div v-if="!reviewMode && viewMode === 'grid' && selectedIds.length > 0" class="batch-bar">
      <span>已选择 {{ selectedIds.length }} 张</span>
      <button class="primary-btn" type="button" @click="batchConfirm()">确认选中</button>
      <button class="danger-outline" type="button" @click="batchReject()">拒绝选中</button>
      <button class="danger-outline danger-text" type="button" @click="batchDelete()">删除选中</button>
    </div>
  </Transition>
</template>

<style scoped>
.cards-page {
  position: relative;
  z-index: 1;
  height: 100vh;
  padding-top: 48px;
  overflow-y: auto;
  box-sizing: border-box;
}

.cards-page > .cards-shell {
  padding: 24px 20px 32px;
}

.cards-shell {
  max-width: 1180px;
  margin: 0 auto;
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  background: rgba(15, 15, 25, 0.58);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  box-shadow: var(--shadow-md);
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 18px;
}

h1 {
  margin: 0;
  color: var(--text-primary);
  font-size: 26px;
}

.page-head p {
  margin: 6px 0 0;
  color: var(--text-secondary);
}

.stats-strip {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 18px;
}

.stat-item {
  padding: 12px;
  border-radius: var(--radius-md);
  background: rgba(99, 102, 241, 0.07);
  border: 1px solid var(--border);
}

.stat-item span {
  display: block;
  color: var(--text-secondary);
  font-size: 12px;
}

.stat-item strong {
  display: block;
  margin-top: 4px;
  color: var(--text-primary);
  font-size: 22px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.search {
  max-width: 320px;
}

.status-select {
  width: 140px;
}

.view-switch {
  margin-left: auto;
  flex-shrink: 0;
}

.primary-btn,
.ghost-btn,
.icon-btn {
  height: 34px;
  border-radius: var(--radius-sm);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  white-space: nowrap;
}

.primary-btn {
  padding: 0 16px;
  border: 0;
  color: #fff;
  background: var(--gradient-brand);
  box-shadow: 0 6px 20px rgba(99, 102, 241, 0.24);
}

.ghost-btn {
  padding: 0 14px;
  border: 1px solid var(--border);
  color: var(--text-primary);
  background: transparent;
}

.ghost-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.danger-text {
  color: #f87171;
}

.icon-btn {
  width: 34px;
  border: 1px solid var(--border);
  color: var(--text-secondary);
  background: transparent;
}

.ghost-btn:hover,
.icon-btn:hover {
  color: var(--primary-light);
  border-color: var(--primary);
}

.view-switch {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.discover-btn {
  border-color: rgba(52, 211, 153, 0.32);
  color: #34d399;
}

.review-btn.active {
  border-color: rgba(99, 102, 241, 0.5);
  color: var(--primary-light);
  background: rgba(99, 102, 241, 0.12);
}

.view-btn {
  padding: 0 14px;
  height: 30px;
  border: none;
  border-right: 1px solid var(--border);
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.view-btn:last-child {
  border-right: none;
}

.view-btn:hover {
  color: var(--text-primary);
  background: var(--bg-hover);
}

.view-btn.active {
  color: #fff;
  background: var(--gradient-brand);
}

.group-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
  align-items: flex-start;
}

.group-tab-wrap {
  position: relative;
}

.group-tab {
  height: 32px;
  padding: 0 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  transition: all var(--transition-fast);
}

.group-tab:hover {
  color: var(--text-primary);
  border-color: var(--primary);
}

.group-tab.active {
  color: #fff;
  background: var(--gradient-brand);
  border-color: transparent;
}

.tab-count {
  font-size: 11px;
  opacity: 0.65;
}

.expand-arrow {
  font-size: 12px;
  transition: transform 0.2s ease;
}

.expand-arrow.expanded {
  transform: rotate(90deg);
}

.sub-group-panel {
  position: absolute;
  top: 100%;
  left: 0;
  z-index: 10;
  margin-top: 4px;
  padding: 6px;
  min-width: 160px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-glass-heavy);
  backdrop-filter: var(--glass-blur-heavy);
  -webkit-backdrop-filter: var(--glass-blur-heavy);
  box-shadow: var(--shadow-lg);
}

.sub-group-item {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  padding: 6px 10px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  transition: all var(--transition-fast);
}

.sub-group-item:hover {
  color: var(--text-primary);
  background: var(--bg-hover);
}

.sub-group-item.active {
  color: var(--primary-light);
  background: rgba(99, 102, 241, 0.12);
}

.dropdown-enter-active,
.dropdown-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.dropdown-enter-from,
.dropdown-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.select-all-row {
  display: flex;
  align-items: center;
  padding: 8px 0 4px;
  color: var(--text-secondary);
  font-size: 13px;
}

.grid-wrap {
  min-height: 260px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 18px;
}

.batch-bar {
  position: fixed;
  left: 50%;
  bottom: 22px;
  z-index: 24;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-glass-heavy);
  backdrop-filter: var(--glass-blur-heavy);
  -webkit-backdrop-filter: var(--glass-blur-heavy);
  box-shadow: var(--shadow-lg);
  color: var(--text-primary);
}

.danger-outline {
  height: 34px;
  padding: 0 16px;
  border-radius: var(--radius-sm);
  border: 1px solid rgba(248, 113, 113, 0.35);
  color: #f87171;
  background: rgba(248, 113, 113, 0.08);
  cursor: pointer;
}

.batch-enter-active,
.batch-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.batch-enter-from,
.batch-leave-to {
  opacity: 0;
  transform: translate(-50%, 12px);
}

@media (max-width: 760px) {
  .cards-shell {
    padding: 16px;
  }

  .page-head,
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .stats-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .search,
  .status-select {
    max-width: none;
    width: 100%;
  }

  .view-switch {
    margin-left: 0;
  }
}
</style>
