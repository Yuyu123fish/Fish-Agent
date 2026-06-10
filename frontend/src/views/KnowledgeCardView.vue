<script setup lang="ts">
/**
 * 知识卡片主页面：三栏布局承载分组导航、卡片工作区和详情面板。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Link, Plus, Refresh, Search, View } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppHeader from '@/components/AppHeader.vue'
import DrawerSidebar from '@/components/DrawerSidebar.vue'
import CardSidebar from '@/components/CardSidebar.vue'
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
  getCard,
  getCardGroups,
  getCardStats,
  getReviewStats,
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
const cardTypeFilter = ref<'all' | 'concept' | 'topic'>('all')
const sortBy = ref<'default' | 'createdAt' | 'updatedAt' | 'reviewNextAt'>('default')
const sortOrder = ref<'asc' | 'desc'>('desc')
const reviewOverdue = ref(false)
const groupName = ref('all')
const groupId = ref<number | null>(null)
const viewMode = ref<'grid' | 'graph'>('grid')
const reviewMode = ref(false)
const groupTree = ref<GroupTreeNode[]>([])
const expandedCardId = ref<number | null>(null)
const sidebarDrawerVisible = ref(false)
const dueTodayCount = ref(0)
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

const showEmptyGuide = computed(() => !loading.value && stats.value.total === 0)
const visiblePendingIds = computed(() => cards.value.filter((c) => c.status === 'pending').map((c) => c.id))

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

async function loadCards() {
  loading.value = true
  try {
    const [statData, pageData, treeData, reviewStats] = await Promise.all([
      getCardStats(),
      listCards({
        page: page.value,
        size: pageSize.value,
        keyword: keyword.value,
        status: status.value,
        groupName: groupId.value ? undefined : groupName.value,
        groupId: groupId.value,
        cardType: cardTypeFilter.value !== 'all' ? cardTypeFilter.value : undefined,
        reviewOverdue: reviewOverdue.value || undefined,
        sortBy: sortBy.value !== 'default' ? sortBy.value : undefined,
        sortOrder: sortBy.value !== 'default' ? sortOrder.value : undefined
      }),
      getCardGroups(),
      getReviewStats().catch(() => null)
    ])
    stats.value = statData
    cards.value = pageData.records
    total.value = pageData.total
    groupTree.value = treeData
    dueTodayCount.value = reviewStats?.dueToday ?? dueTodayCount.value
    selectedIds.value = selectedIds.value.filter((id) => pageData.records.some((c) => c.id === id))
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载知识卡片失败')
  } finally {
    loading.value = false
  }
}

async function loadDueCount() {
  try {
    const reviewStats = await getReviewStats()
    dueTodayCount.value = reviewStats.dueToday
  } catch {
    // 复习统计不影响主列表渲染，静默降级。
  }
}

function resetAndLoad() {
  expandedCardId.value = null
  if (page.value === 1) void loadCards()
  else page.value = 1
}

function toggleReviewOverdue() {
  reviewOverdue.value = !reviewOverdue.value
  resetAndLoad()
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
  selectedIds.value = checked ? cards.value.map((c) => c.id) : []
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

function openReviewMode() {
  selectedIds.value = []
  reviewMode.value = true
}

function closeReviewMode() {
  reviewMode.value = false
  void loadDueCount()
  void loadCards()
}

function handleExpand(cardId: number | null) {
  expandedCardId.value = expandedCardId.value === cardId ? null : cardId
}

async function handleCardAction(cardId: number, action: 'confirm' | 'edit' | 'reject' | 'delete') {
  if (action === 'confirm') {
    await batchConfirm([cardId])
    return
  }
  if (action === 'reject') {
    await batchReject([cardId])
    return
  }
  if (action === 'edit') {
    try {
      editingCard.value = await getCard(cardId)
      dialogVisible.value = true
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '加载卡片失败')
    }
    return
  }
  try {
    await ElMessageBox.confirm('确定删除这张卡片？删除后不可恢复。', '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteCard(cardId)
    ElMessage.success('已删除')
    await loadCards()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

function handleKeywordClick(kw: string) {
  keyword.value = kw
  resetAndLoad()
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
    <div class="three-column">
      <CardSidebar
        class="desktop-sidebar"
        :group-tree="groupTree"
        :current-group-id="groupId"
        @select="selectGroup"
        @discover="discoveryVisible = true"
      />

      <section class="main-column">
        <header class="page-head">
          <button class="sidebar-trigger" type="button" @click="sidebarDrawerVisible = true">☰ 分组</button>
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
          <div class="stat-item">
            <span>到期复习</span>
            <strong>{{ dueTodayCount }}</strong>
          </div>
        </section>

        <EmptyCardGuide v-if="showEmptyGuide" @create="openCreate" />

        <template v-else>
          <section class="toolbar-primary">
            <el-input
              v-model="keyword"
              class="search"
              clearable
              placeholder="搜索标题或内容"
              :prefix-icon="Search"
              @keyup.enter="resetAndLoad"
              @clear="resetAndLoad"
            />
            <button class="primary-btn" type="button" @click="openCreate">
              <el-icon><Plus /></el-icon>
              创建
            </button>
            <div class="view-switch">
              <button type="button" class="view-btn" :class="{ active: viewMode === 'grid' }" @click="viewMode = 'grid'">☐ 卡片</button>
              <button type="button" class="view-btn" :class="{ active: viewMode === 'graph' }" @click="viewMode = 'graph'">◉ 图谱</button>
            </div>
            <button class="ghost-btn review-btn" type="button" :class="{ active: reviewMode }" @click="openReviewMode">
              <el-icon><View /></el-icon>
              复习
              <span v-if="dueTodayCount" class="badge">{{ dueTodayCount }}</span>
            </button>
            <button class="icon-btn" title="刷新" type="button" @click="loadCards">
              <el-icon><Refresh /></el-icon>
            </button>
          </section>

          <section class="toolbar-filters">
            <el-select v-model="status" class="filter-select" @change="resetAndLoad">
              <el-option label="全部状态" value="all" />
              <el-option label="已确认" value="confirmed" />
              <el-option label="待确认" value="pending" />
              <el-option label="已拒绝" value="rejected" />
            </el-select>
            <el-select v-model="cardTypeFilter" class="filter-select" @change="resetAndLoad">
              <el-option label="全部类型" value="all" />
              <el-option label="概念" value="concept" />
              <el-option label="主题" value="topic" />
            </el-select>
            <el-select v-model="sortBy" class="filter-select" @change="resetAndLoad">
              <el-option label="默认排序" value="default" />
              <el-option label="最近创建" value="createdAt" />
              <el-option label="最近更新" value="updatedAt" />
              <el-option label="复习到期" value="reviewNextAt" />
            </el-select>
            <el-select v-if="sortBy !== 'default'" v-model="sortOrder" class="order-select" @change="resetAndLoad">
              <el-option label="降序" value="desc" />
              <el-option label="升序" value="asc" />
            </el-select>
            <button class="ghost-btn" type="button" :class="{ active: reviewOverdue }" @click="toggleReviewOverdue">
              复习到期
            </button>
            <button class="ghost-btn" type="button" :disabled="visiblePendingIds.length === 0" @click="batchConfirm(visiblePendingIds)">
              {{ visiblePendingIds.length > 0 ? `全部确认 (${visiblePendingIds.length})` : '无待确认' }}
            </button>
            <button class="ghost-btn danger-text" type="button" :disabled="visiblePendingIds.length === 0" @click="batchReject(visiblePendingIds)">
              {{ visiblePendingIds.length > 0 ? `全部拒绝 (${visiblePendingIds.length})` : '无待确认' }}
            </button>
            <button class="ghost-btn discover-btn" type="button" @click="discoveryVisible = true">
              <el-icon><Link /></el-icon>
              发现关联
            </button>
          </section>

          <CardReviewMode
            v-if="reviewMode"
            :group-id="groupId"
            :group-name="groupName"
            @close="closeReviewMode"
            @reviewed="loadDueCount"
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
                :expanded-id="expandedCardId"
                @select="selectCard"
                @toggle="toggleSelection"
                @expand="handleExpand"
                @action="handleCardAction"
                @keyword-click="handleKeywordClick"
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

      <CardDetailPanel
        v-if="detailVisible"
        :key="detailRefreshKey"
        :card-id="selectedCardId"
        @close="detailVisible = false"
        @edit="openEdit"
        @deleted="handleDeleted"
      />
    </div>
  </main>

  <el-drawer v-model="sidebarDrawerVisible" direction="ltr" size="260px" :show-close="false">
    <CardSidebar
      :group-tree="groupTree"
      :current-group-id="groupId"
      @select="(id, name) => { selectGroup(id, name); sidebarDrawerVisible = false }"
      @discover="() => { discoveryVisible = true; sidebarDrawerVisible = false }"
    />
  </el-drawer>

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
  overflow: hidden;
  box-sizing: border-box;
}

.three-column {
  display: flex;
  height: 100%;
  overflow: hidden;
}

.main-column {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 24px 20px 32px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 18px;
}

.sidebar-trigger {
  display: none;
  height: 34px;
  padding: 0 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  color: var(--text-primary);
  background: transparent;
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
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 18px;
}

.stat-item {
  padding: 12px;
  border-radius: var(--radius-md);
  background: var(--bg-hover);
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

.toolbar-primary,
.toolbar-filters {
  display: flex;
  align-items: center;
  gap: 10px;
}

.toolbar-primary {
  margin-bottom: 8px;
}

.toolbar-filters {
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.search {
  flex: 1;
  min-width: 220px;
  max-width: 440px;
}

.filter-select {
  width: 140px;
}

.order-select {
  width: 100px;
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
  letter-spacing: 0;
}

.primary-btn {
  padding: 0 16px;
  border: 0;
  color: var(--bg-base);
  background: var(--accent);
}

.ghost-btn {
  padding: 0 14px;
  border: 1px solid var(--border);
  color: var(--text-primary);
  background: transparent;
}

.ghost-btn.active {
  border-color: var(--border-bright);
  background: var(--bg-active);
}

.ghost-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.danger-text {
  color: var(--status-error);
}

.icon-btn {
  width: 34px;
  border: 1px solid var(--border);
  color: var(--text-secondary);
  background: transparent;
}

.ghost-btn:hover,
.icon-btn:hover {
  color: var(--text-primary);
  border-color: var(--border-bright);
}

.view-switch {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.discover-btn {
  border-color: var(--status-info);
  color: var(--status-info);
}

.review-btn.active {
  border-color: var(--border-bright);
  color: var(--text-primary);
  background: var(--bg-active);
}

.review-btn .badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  color: var(--bg-base);
  background: var(--status-warning);
  font-size: 11px;
  font-weight: 700;
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
  color: var(--bg-base);
  background: var(--accent);
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
  background: var(--bg-surface);
  box-shadow: var(--shadow-lg);
  color: var(--text-primary);
}

.danger-outline {
  height: 34px;
  padding: 0 16px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--status-error);
  color: var(--status-error);
  background: rgba(143, 107, 107, 0.08);
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

@media (max-width: 1199px) {
  .desktop-sidebar {
    width: 48px !important;
  }

  .stats-strip {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 767px) {
  .desktop-sidebar {
    display: none;
  }

  .main-column {
    width: 100%;
    padding: 16px;
  }

  .page-head,
  .toolbar-primary {
    flex-direction: column;
    align-items: stretch;
  }

  .sidebar-trigger {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    align-self: flex-start;
  }

  .stats-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .toolbar-filters {
    align-items: stretch;
  }

  .search,
  .filter-select,
  .order-select {
    max-width: none;
    width: 100%;
  }

  .view-switch {
    margin-left: 0;
  }
}
</style>
