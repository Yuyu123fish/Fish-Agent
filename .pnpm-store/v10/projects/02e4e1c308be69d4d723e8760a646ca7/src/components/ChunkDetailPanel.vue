<script setup lang="ts">
/**
 * 文档切片详情面板。
 *
 * 面板内分为“概览/主题分组”和“切片列表”两级视图，数据加载集中在本组件，父页面只负责传入 taskId。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Close, Link, Search } from '@element-plus/icons-vue'
import ChunkCardLinks from '@/components/ChunkCardLinks.vue'
import {
  getChunkGroups,
  getChunkRelatedCards,
  getDocumentChunks,
  type ChunkGroupItem,
  type ChunkGroupResult,
  type ChunkItem,
  type RelatedCard
} from '@/api/knowledge'

const props = defineProps<{
  visible: boolean
  taskId?: string | null
  focusChunkIndex?: number | null
}>()

const emit = defineEmits<{
  close: []
  openCard: [cardId: number]
}>()

type LinkItem = {
  key: string | number
  title: string
  description?: string
  meta?: string
  similarity?: number
}

const loading = ref(false)
const chunkLoading = ref(false)
const groupData = ref<ChunkGroupResult | null>(null)
const activeGroup = ref<ChunkGroupItem | null>(null)
const chunks = ref<ChunkItem[]>([])
const chunkTotal = ref(0)
const page = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const relatedCards = ref<Record<number, RelatedCard[]>>({})
const expandedChunk = ref<number | null>(null)

const title = computed(() => groupData.value?.fileName || '文档切片')
const inChunkList = computed(() => activeGroup.value != null || keyword.value.trim().length > 0)

watch(
  () => [props.visible, props.taskId] as const,
  async () => {
    if (!props.visible || !props.taskId) return
    await loadGroups()
    if (props.focusChunkIndex != null) {
      activeGroup.value = null
      await loadChunks()
      expandedChunk.value = props.focusChunkIndex
    }
  },
  { immediate: true }
)

watch([page, pageSize], () => {
  if (props.visible && props.taskId && inChunkList.value) void loadChunks()
})

async function loadGroups() {
  if (!props.taskId) return
  loading.value = true
  activeGroup.value = null
  relatedCards.value = {}
  expandedChunk.value = null
  try {
    groupData.value = await getChunkGroups(props.taskId)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载切片分组失败')
    emit('close')
  } finally {
    loading.value = false
  }
}

async function openGroup(group: ChunkGroupItem) {
  activeGroup.value = group
  keyword.value = ''
  page.value = 1
  await loadChunks()
}

async function searchChunks() {
  activeGroup.value = null
  page.value = 1
  await loadChunks()
}

async function loadChunks() {
  if (!props.taskId) return
  chunkLoading.value = true
  relatedCards.value = {}
  expandedChunk.value = null
  try {
    const data = await getDocumentChunks(props.taskId, {
      page: page.value,
      size: pageSize.value,
      keyword: keyword.value,
      groupIndex: activeGroup.value?.groupIndex ?? null
    })
    chunks.value = data.chunks
    chunkTotal.value = data.total
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载切片失败')
  } finally {
    chunkLoading.value = false
  }
}

function backToOverview() {
  activeGroup.value = null
  keyword.value = ''
  chunks.value = []
  chunkTotal.value = 0
  expandedChunk.value = null
}

async function toggleRelated(chunk: ChunkItem) {
  if (!props.taskId) return
  if (expandedChunk.value === chunk.chunkIndex) {
    expandedChunk.value = null
    return
  }
  expandedChunk.value = chunk.chunkIndex
  if (relatedCards.value[chunk.chunkIndex]) return
  try {
    const cards = await getChunkRelatedCards(props.taskId, chunk.chunkIndex)
    relatedCards.value = { ...relatedCards.value, [chunk.chunkIndex]: cards }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载关联卡片失败')
  }
}

function cardLinks(chunkIndex: number): LinkItem[] {
  return (relatedCards.value[chunkIndex] ?? []).map((card) => ({
    key: card.cardId,
    title: card.title,
    meta: card.cardType === 'topic' ? '主题卡片' : '概念卡片',
    similarity: card.similarity
  }))
}

function openCard(item: LinkItem) {
  emit('openCard', Number(item.key))
}

function preview(text: string, limit = 150): string {
  const raw = text?.replace(/\s+/g, ' ').trim() ?? ''
  return raw.length <= limit ? raw : `${raw.slice(0, limit)}...`
}
</script>

<template>
  <Teleport to="body">
    <Transition name="panel">
      <div v-if="visible" class="panel-mask" @click="emit('close')">
        <aside class="chunk-panel" @click.stop>
          <button class="close-btn" title="关闭" type="button" @click="emit('close')">
            <el-icon><Close /></el-icon>
          </button>

          <div v-loading="loading" class="panel-body">
            <header class="panel-head">
              <button v-if="inChunkList" class="back-btn" type="button" @click="backToOverview">
                <el-icon><ArrowLeft /></el-icon>
              </button>
              <div>
                <span class="eyebrow">切片可视化</span>
                <h2>{{ title }}</h2>
              </div>
            </header>

            <template v-if="groupData">
              <section class="search-row">
                <el-input
                  v-model="keyword"
                  clearable
                  placeholder="搜索切片内容"
                  :prefix-icon="Search"
                  @keyup.enter="searchChunks"
                  @clear="searchChunks"
                />
                <button class="ghost-btn" type="button" @click="searchChunks">搜索</button>
              </section>

              <template v-if="!inChunkList">
                <section class="summary">
                  <span>共 {{ groupData.totalChunks }} 个切片</span>
                  <p>{{ groupData.summary }}</p>
                </section>

                <section class="group-list">
                  <button
                    v-for="group in groupData.groups"
                    :key="group.groupIndex"
                    class="group-item"
                    type="button"
                    @click="openGroup(group)"
                  >
                    <strong>{{ group.title }}</strong>
                    <span>{{ group.chunkCount }} 个切片</span>
                  </button>
                </section>
              </template>

              <template v-else>
                <header class="list-head">
                  <div>
                    <strong>{{ activeGroup ? activeGroup.title : '搜索结果' }}</strong>
                    <span>{{ chunkTotal }} 个切片</span>
                  </div>
                </header>

                <section v-loading="chunkLoading" class="chunk-list">
                  <article v-for="chunk in chunks" :key="chunk.chunkIndex" class="chunk-item">
                    <div class="chunk-title">
                      <strong>#{{ chunk.chunkIndex }}</strong>
                      <span>{{ chunk.charCount }} 字</span>
                    </div>
                    <p>{{ preview(chunk.content) }}</p>
                    <button
                      class="related-btn"
                      type="button"
                      :disabled="chunk.relatedCardCount === 0"
                      @click="toggleRelated(chunk)"
                    >
                      <el-icon><Link /></el-icon>
                      {{ chunk.relatedCardCount }} 张关联卡片
                    </button>
                    <ChunkCardLinks
                      v-if="expandedChunk === chunk.chunkIndex"
                      :items="cardLinks(chunk.chunkIndex)"
                      empty-text="暂无相似卡片"
                      action-text="打开卡片"
                      @open="openCard"
                    />
                  </article>
                  <el-empty v-if="!chunkLoading && chunks.length === 0" description="没有匹配的切片" />
                </section>

                <el-pagination
                  v-model:current-page="page"
                  v-model:page-size="pageSize"
                  class="pager"
                  :page-sizes="[10, 20, 50]"
                  :total="chunkTotal"
                  layout="prev, pager, next, sizes"
                  small
                />
              </template>
            </template>
          </div>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.panel-mask {
  position: fixed;
  inset: 0;
  z-index: 25;
  background: rgba(0, 0, 0, 0.3);
}

.chunk-panel {
  position: absolute;
  top: 0;
  right: 0;
  width: 460px;
  max-width: 94vw;
  height: 100%;
  overflow-y: auto;
  border-left: 1px solid var(--border);
  background: var(--bg-surface);
  box-shadow: var(--shadow-lg);
}

.panel-body {
  min-height: 100%;
  padding: 22px;
}

.close-btn,
.back-btn {
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: transparent;
  cursor: pointer;
}

.close-btn {
  position: absolute;
  top: 14px;
  right: 14px;
  z-index: 2;
}

.close-btn:hover,
.back-btn:hover {
  color: var(--text-primary);
  background: var(--bg-hover);
}

.panel-head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding-right: 38px;
  margin-bottom: 16px;
}

.eyebrow {
  color: var(--text-secondary);
  font-size: 12px;
}

h2 {
  margin: 4px 0 0;
  color: var(--text-primary);
  font-size: 22px;
  line-height: 1.4;
}

.search-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
  margin-bottom: 14px;
}

.ghost-btn {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  background: transparent;
  cursor: pointer;
}

.summary {
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-hover);
}

.summary span {
  color: var(--text-secondary);
  font-size: 12px;
}

.summary p {
  margin: 8px 0 0;
  color: var(--text);
  line-height: 1.75;
  font-size: 13px;
}

.group-list {
  display: grid;
  gap: 10px;
  margin-top: 14px;
}

.group-item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  background: var(--bg-elevated);
  cursor: pointer;
  text-align: left;
}

.group-item:hover {
  border-color: var(--border-bright);
  background: var(--bg-active);
}

.group-item span,
.list-head span {
  flex-shrink: 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.list-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 10px;
  color: var(--text-primary);
}

.list-head div {
  display: flex;
  align-items: center;
  gap: 8px;
}

.chunk-list {
  min-height: 260px;
  display: grid;
  gap: 10px;
}

.chunk-item {
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-elevated);
}

.chunk-title {
  display: flex;
  justify-content: space-between;
  color: var(--text-primary);
  font-size: 13px;
}

.chunk-title span {
  color: var(--text-secondary);
}

.chunk-item p {
  margin: 8px 0 10px;
  color: var(--text);
  line-height: 1.7;
  font-size: 13px;
}

.related-btn {
  height: 28px;
  margin-bottom: 8px;
  padding: 0 10px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--status-ok);
  border-radius: var(--radius-sm);
  color: var(--status-ok);
  background: rgba(107, 143, 113, 0.08);
  cursor: pointer;
}

.related-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.panel-enter-active,
.panel-leave-active {
  transition: opacity 0.24s ease-out;
}

.panel-enter-active .chunk-panel,
.panel-leave-active .chunk-panel {
  transition: transform 0.24s ease-out;
}

.panel-enter-from,
.panel-leave-to {
  opacity: 0;
}

.panel-enter-from .chunk-panel,
.panel-leave-to .chunk-panel {
  transform: translateX(100%);
}
</style>
