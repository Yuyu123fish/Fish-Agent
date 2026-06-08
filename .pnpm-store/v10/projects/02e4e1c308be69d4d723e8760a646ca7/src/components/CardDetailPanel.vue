<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { marked } from 'marked'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, Delete, Edit, Link, Plus, Check, CircleClose } from '@element-plus/icons-vue'
import ChunkCardLinks from '@/components/ChunkCardLinks.vue'
import {
  addCardRelation,
  batchConfirmCards,
  batchRejectCards,
  deleteCard,
  getCard,
  listCards,
  mergeCards,
  type CardDetail,
  type CardListItem
} from '@/api/card'

const router = useRouter()

const props = defineProps<{
  visible: boolean
  cardId?: number | null
}>()

const emit = defineEmits<{
  close: []
  edit: [card: CardDetail]
  deleted: []
}>()

const loading = ref(false)
const card = ref<CardDetail | null>(null)
const currentCardId = ref<number | null>(null)
const relationDialogVisible = ref(false)
const relationKeyword = ref('')
const relationType = ref('related_to')
const relationTargetId = ref<number | null>(null)
const relationCandidates = ref<CardListItem[]>([])
const relationLoading = ref(false)

type LinkItem = {
  key: string | number
  title: string
  description?: string
  meta?: string
  similarity?: number
}

const html = computed(() => (card.value?.content ? (marked.parse(card.value.content) as string) : ''))
const sourceChunkLinks = computed<LinkItem[]>(() =>
  (card.value?.relatedChunks ?? []).map((chunk) => ({
    key: `${chunk.taskId}:${chunk.chunkIndex}`,
    title: chunk.fileName,
    description: chunk.contentPreview,
    meta: `切片 #${chunk.chunkIndex}`,
    similarity: chunk.similarity
  }))
)

watch(
  () => [props.visible, props.cardId] as const,
  async () => {
    if (!props.visible || !props.cardId) return
    currentCardId.value = props.cardId
    await loadDetail(props.cardId)
  },
  { immediate: true }
)

async function loadDetail(id: number) {
  loading.value = true
  try {
    card.value = await getCard(id)
    currentCardId.value = id
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载卡片失败')
    emit('close')
  } finally {
    loading.value = false
  }
}

function typeLabel(type?: string): string {
  return type === 'topic' ? '主题' : '概念'
}

function sourceLabel(source?: string): string {
  if (source === 'chat') return '来自对话'
  if (source === 'knowledge') return '来自知识库'
  return '手动创建'
}

async function handleDelete() {
  if (!card.value) return
  try {
    await ElMessageBox.confirm(`确定删除「${card.value.title}」？删除后不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteCard(card.value.id)
    ElMessage.success('卡片已删除')
    emit('deleted')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

async function handleConfirm() {
  if (!card.value) return
  try {
    await batchConfirmCards([card.value.id])
    ElMessage.success('卡片已确认')
    await loadDetail(card.value.id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '确认失败')
  }
}

async function handleReject() {
  if (!card.value) return
  try {
    await ElMessageBox.confirm(`拒绝「${card.value.title}」？卡片将移入"已拒绝"，可稍后查看。`, '拒绝确认', {
      type: 'warning',
      confirmButtonText: '拒绝',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await batchRejectCards([card.value.id])
    ElMessage.success('卡片已拒绝')
    emit('deleted')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '拒绝失败')
  }
}

async function switchRelation(cardId: number) {
  await loadDetail(cardId)
}

async function handleMerge(discardId: number) {
  if (!card.value) return
  try {
    await ElMessageBox.confirm('确定将关联卡片合并到当前卡片？合并后关联卡片会被删除。', '合并确认', {
      type: 'warning',
      confirmButtonText: '合并',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await mergeCards(card.value.id, discardId)
    ElMessage.success('卡片已合并')
    await loadDetail(card.value.id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '合并失败')
  }
}

function relationLabel(type: string): string {
  if (type === 'contains') return '包含'
  if (type === 'precedes') return '前置'
  if (type === 'derived_from') return '衍生'
  return '相关'
}

async function openRelationDialog() {
  relationDialogVisible.value = true
  relationTargetId.value = null
  relationKeyword.value = ''
  relationType.value = 'related_to'
  await searchRelationCandidates()
}

async function searchRelationCandidates() {
  relationLoading.value = true
  try {
    const page = await listCards({
      page: 1,
      size: 10,
      status: 'confirmed',
      keyword: relationKeyword.value
    })
    relationCandidates.value = page.records.filter((item) => item.id !== currentCardId.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '搜索卡片失败')
  } finally {
    relationLoading.value = false
  }
}

async function submitRelation() {
  if (!currentCardId.value || !relationTargetId.value) {
    ElMessage.warning('请选择要关联的卡片')
    return
  }
  try {
    await addCardRelation(currentCardId.value, relationTargetId.value, relationType.value)
    ElMessage.success('关联已添加')
    relationDialogVisible.value = false
    await loadDetail(currentCardId.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '添加关联失败')
  }
}

function openSourceChunk(item: LinkItem) {
  const [taskId, chunkIndex] = String(item.key).split(':')
  void router.push({
    path: '/knowledge',
    query: {
      openChunks: taskId,
      chunkIndex
    }
  })
}
</script>

<template>
  <Teleport to="body">
    <Transition name="panel">
      <div v-if="visible" class="panel-mask" @click="emit('close')">
        <aside class="detail-panel" @click.stop>
          <button class="close-btn" title="关闭" @click="emit('close')">
            <el-icon><Close /></el-icon>
          </button>

          <div v-if="loading" class="loading">加载中…</div>
          <template v-else-if="card">
            <header class="panel-head">
              <span class="type-badge">{{ typeLabel(card.cardType) }}</span>
              <h2>{{ card.title }}</h2>
              <div class="source">{{ sourceLabel(card.sourceType) }}</div>
            </header>

            <section class="markdown-body content" v-html="html" />

            <section class="kv">
              <div>
                <span class="label">分组</span>
                <span v-if="card.groupPath" class="group-breadcrumb">
                  <span v-for="(segment, idx) in card.groupPath.split(' > ')" :key="idx" class="breadcrumb-segment">
                    <span v-if="idx > 0" class="breadcrumb-sep">&gt;</span>
                    {{ segment }}
                  </span>
                </span>
                <span v-else>{{ card.groupName || '未分组' }}</span>
              </div>
              <div>
                <span class="label">状态</span>
                <span>{{ card.status }}</span>
              </div>
            </section>

            <section class="keywords">
              <el-tag v-for="kw in card.keywords ?? []" :key="kw" size="small" effect="plain">{{ kw }}</el-tag>
            </section>

            <section class="relations">
              <div class="relations-head">
                <h3>关联卡片</h3>
                <button class="mini-btn" type="button" @click="openRelationDialog">
                  <el-icon><Plus /></el-icon>
                  添加
                </button>
              </div>
              <div v-if="(card.relations ?? []).length === 0" class="empty-rel">暂无关联</div>
              <div v-for="rel in card.relations ?? []" v-else :key="rel.id" class="rel-item">
                <button class="rel-title" type="button" @click="switchRelation(rel.cardId)">{{ rel.cardTitle }}</button>
                <small>
                  <el-icon><Link /></el-icon>
                  {{ relationLabel(rel.relationType) }} · {{ rel.direction }} · {{ rel.confidence.toFixed(2) }}
                </small>
                <button
                  v-if="rel.confidence > 0.9"
                  class="merge-btn"
                  type="button"
                  @click="handleMerge(rel.cardId)"
                >
                  疑似重复，合并到此卡片
                </button>
              </div>
            </section>

            <section v-if="card.sourceType === 'knowledge'" class="source-chunks">
              <h3>源文档切片</h3>
              <ChunkCardLinks
                :items="sourceChunkLinks"
                empty-text="暂无相似源切片"
                action-text="查看切片"
                @open="openSourceChunk"
              />
            </section>

            <footer class="actions">
              <button class="ghost-btn" type="button" @click="emit('edit', card)">
                <el-icon><Edit /></el-icon>
                编辑
              </button>
              <button v-if="card.status === 'pending'" class="confirm-btn" type="button" @click="handleConfirm">
                <el-icon><Check /></el-icon>
                确认
              </button>
              <button v-if="card.status === 'pending'" class="warn-btn" type="button" @click="handleReject">
                <el-icon><CircleClose /></el-icon>
                拒绝
              </button>
              <button class="danger-btn" type="button" @click="handleDelete">
                <el-icon><Delete /></el-icon>
                删除
              </button>
            </footer>
          </template>
        </aside>
      </div>
    </Transition>

    <el-dialog v-model="relationDialogVisible" title="添加关联" width="520px">
      <div class="relation-form">
        <el-input
          v-model="relationKeyword"
          clearable
          placeholder="搜索已确认卡片"
          @keyup.enter="searchRelationCandidates"
          @clear="searchRelationCandidates"
        />
        <button class="ghost-btn" type="button" @click="searchRelationCandidates">搜索</button>
        <el-select v-model="relationTargetId" class="target-select" :loading="relationLoading" placeholder="选择卡片">
          <el-option v-for="item in relationCandidates" :key="item.id" :label="item.title" :value="item.id" />
        </el-select>
        <el-select v-model="relationType" class="target-select">
          <el-option label="相关" value="related_to" />
          <el-option label="包含" value="contains" />
          <el-option label="前置" value="precedes" />
          <el-option label="衍生" value="derived_from" />
        </el-select>
      </div>
      <template #footer>
        <button class="ghost-btn" type="button" @click="relationDialogVisible = false">取消</button>
        <button class="primary-btn" type="button" @click="submitRelation">添加</button>
      </template>
    </el-dialog>
  </Teleport>
</template>

<style scoped>
.panel-mask {
  position: fixed;
  inset: 0;
  z-index: 25;
  background: rgba(0, 0, 0, 0.3);
}

.detail-panel {
  position: absolute;
  top: 0;
  right: 0;
  width: 420px;
  max-width: 92vw;
  height: 100%;
  padding: 22px;
  overflow-y: auto;
  background: var(--bg-surface);
  border-left: 1px solid var(--border);
  box-shadow: var(--shadow-lg);
}

.close-btn {
  position: absolute;
  top: 14px;
  right: 14px;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: transparent;
  cursor: pointer;
}

.close-btn:hover {
  color: var(--text-primary);
  background: var(--bg-hover);
}

.loading {
  color: var(--text-secondary);
  padding: 60px 0;
  text-align: center;
}

.panel-head {
  padding-right: 36px;
  margin-bottom: 18px;
}

.type-badge {
  display: inline-flex;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: var(--bg-hover);
  border: 1px solid var(--border);
  font-size: 12px;
}

h2 {
  margin: 10px 0 6px;
  color: var(--text-primary);
  font-size: 22px;
  line-height: 1.45;
}

.source {
  color: var(--text-secondary);
  font-size: 13px;
}

.content {
  padding: 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  background: var(--bg-sunken);
}

.kv {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin: 14px 0;
}

.kv > div {
  padding: 10px;
  border-radius: var(--radius-sm);
  background: var(--bg-hover);
}

.label {
  display: block;
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 4px;
}

.group-breadcrumb {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}

.breadcrumb-segment {
  font-size: 13px;
  color: var(--text-primary);
}

.breadcrumb-sep {
  font-size: 11px;
  color: var(--text-muted);
  margin: 0 2px;
}

.keywords {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.relations {
  margin-top: 22px;
}

.source-chunks {
  margin-top: 22px;
}

.relations-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.relations h3 {
  margin: 0 0 10px;
  color: var(--text-primary);
  font-size: 15px;
}

.source-chunks h3 {
  margin: 0 0 10px;
  color: var(--text-primary);
  font-size: 15px;
}

.relations-head h3 {
  margin: 0;
}

.empty-rel,
.rel-item {
  padding: 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  color: var(--text-secondary);
  background: var(--bg-elevated);
}

.rel-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: var(--text);
  margin-bottom: 8px;
}

.rel-title {
  padding: 0;
  border: 0;
  color: var(--text-primary);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.rel-title:hover {
  color: var(--text-primary);
}

.rel-item small {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--text-secondary);
}

.mini-btn,
.merge-btn {
  height: 28px;
  border-radius: var(--radius-sm);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}

.mini-btn {
  padding: 0 8px;
  border: 1px solid var(--border);
  color: var(--text-secondary);
  background: transparent;
}

.merge-btn {
  align-self: flex-start;
  padding: 0 10px;
  border: 1px solid var(--status-warning);
  color: var(--status-warning);
  background: rgba(143, 135, 107, 0.08);
}

.actions {
  display: flex;
  gap: 10px;
  margin-top: 24px;
}

.ghost-btn,
.danger-btn {
  height: 34px;
  padding: 0 16px;
  border-radius: var(--radius-sm);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.primary-btn {
  height: 34px;
  padding: 0 18px;
  border: 0;
  border-radius: var(--radius-sm);
  color: var(--bg-base);
  background: var(--accent);
  cursor: pointer;
}

.relation-form {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
}

.target-select {
  grid-column: 1 / -1;
}

.ghost-btn {
  border: 1px solid var(--border);
  color: var(--text-primary);
  background: transparent;
}

.confirm-btn {
  border: 1px solid var(--status-ok);
  color: var(--status-ok);
  background: rgba(107, 143, 113, 0.08);
}

.warn-btn {
  border: 1px solid var(--status-warning);
  color: var(--status-warning);
  background: rgba(143, 135, 107, 0.08);
}

.danger-btn {
  border: 1px solid var(--status-error);
  color: var(--status-error);
  background: rgba(143, 107, 107, 0.08);
}

.panel-enter-active,
.panel-leave-active {
  transition: opacity 0.3s ease-out;
}

.panel-enter-active .detail-panel,
.panel-leave-active .detail-panel {
  transition: transform 0.3s ease-out;
}

.panel-enter-from,
.panel-leave-to {
  opacity: 0;
}

.panel-enter-from .detail-panel,
.panel-leave-to .detail-panel {
  transform: translateX(100%);
}
</style>
