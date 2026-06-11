<script setup lang="ts">
/**
 * 知识库管理页：上传、任务列表、删除。
 * 上传逻辑复用 KnowledgeUpload，列表与删除通过 knowledgeApi。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { Refresh, View } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import * as knowledgeApi from '@/api/knowledge'
import type { DocumentMetadataItem } from '@/api/knowledge'
import KnowledgeUpload from '@/components/KnowledgeUpload.vue'
import AppHeader from '@/components/AppHeader.vue'
import DrawerSidebar from '@/components/DrawerSidebar.vue'
import ChunkDetailPanel from '@/components/ChunkDetailPanel.vue'
import { useResponsive } from '@/composables/useResponsive'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const { role } = storeToRefs(auth)
const { isMobile } = useResponsive()

const isAdmin = computed(() => (role.value ?? '').toUpperCase() === 'ADMIN')

const activeTab = ref<'mine' | 'all'>('mine')
const loading = ref(false)
const records = ref<DocumentMetadataItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const chunkPanelVisible = ref(false)
const activeChunkTaskId = ref<string | null>(null)
const focusChunkIndex = ref<number | null>(null)

async function loadList() {
  loading.value = true
  try {
    const data =
      isAdmin.value && activeTab.value === 'all'
        ? await knowledgeApi.listAllDocuments(page.value, pageSize.value)
        : await knowledgeApi.listMyDocuments(page.value, pageSize.value)
    records.value = data.records
    total.value = data.total
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadList()
  openChunksFromRoute()
})
watch([page, pageSize], () => void loadList())
watch(() => route.query.openChunks, () => openChunksFromRoute())

function onTabChange() {
  if (page.value === 1) {
    void loadList()
  } else {
    page.value = 1
  }
}

function formatSize(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

function statusType(s: string): 'success' | 'warning' | 'info' | 'danger' {
  const u = (s ?? '').toUpperCase()
  if (u === 'SUCCESS') return 'success'
  if (u === 'FAILED') return 'danger'
  if (u === 'PROCESSING') return 'warning'
  return 'info'
}

async function onDelete(row: DocumentMetadataItem) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.fileName}」？将清除 ES 切片与对象存储。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await knowledgeApi.deleteDocument(row.taskId)
    ElMessage.success('已删除')
    await loadList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

function goChat() {
  void router.push('/chat')
}

function openChunkDetail(taskId: string, chunkIndex?: number | null) {
  activeChunkTaskId.value = taskId
  focusChunkIndex.value = chunkIndex ?? null
  chunkPanelVisible.value = true
}

function closeChunkDetail() {
  chunkPanelVisible.value = false
  activeChunkTaskId.value = null
  focusChunkIndex.value = null
}

function openChunksFromRoute() {
  const taskId = typeof route.query.openChunks === 'string' ? route.query.openChunks : null
  if (!taskId) return
  const rawChunkIndex = typeof route.query.chunkIndex === 'string' ? Number(route.query.chunkIndex) : null
  openChunkDetail(taskId, Number.isFinite(rawChunkIndex) ? rawChunkIndex : null)
}

function openCardFromChunk(cardId: number) {
  void router.push({ path: '/cards', query: { openCard: String(cardId) } })
}
</script>

<template>
  <!-- 单根节点：App.vue 的路由 Transition(out-in) 要求视图组件只有一个根元素，否则离开过渡无法完成，页面会卡住无法切换。 -->
  <div class="kb-view">
  <AppHeader :show-back="true" @back="goChat" />
  <DrawerSidebar />
  <div class="kb-page">
    <div class="kb-container">
      <header class="kb-header">
        <h1 class="kb-title">📚 知识库</h1>
        <button class="icon-btn" title="刷新" @click="loadList">
          <el-icon><Refresh /></el-icon>
        </button>
      </header>

      <div class="kb-content">
        <section class="kb-upload">
        <KnowledgeUpload :disabled="false" @ingest-success="loadList" />
        <p class="hint">小文件直传，大文件自动分片；解析进度见下方列表状态。</p>
      </section>

      <el-tabs v-model="activeTab" class="kb-tabs" @tab-change="onTabChange">
        <el-tab-pane label="我的文档" name="mine" />
        <el-tab-pane v-if="isAdmin" label="全部文档（管理员）" name="all" />
      </el-tabs>

      <el-table v-if="!isMobile" v-loading="loading" :data="records" stripe class="kb-table" empty-text="暂无上传记录">
        <el-table-column prop="fileName" label="文件名" min-width="160" show-overflow-tooltip />
        <el-table-column label="大小" width="90">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column prop="scopeType" label="范围" width="88" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="切片数" width="80">
          <template #default="{ row }">{{ row.chunkCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="170" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'SUCCESS'"
              type="primary"
              link
              size="small"
              @click="openChunkDetail(row.taskId)"
            >
              <el-icon><View /></el-icon>
              查看切片
            </el-button>
            <el-button type="danger" link size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-else v-loading="loading" class="kb-mobile-list">
        <div v-for="row in records" :key="row.taskId" class="kb-mobile-card">
          <div class="mobile-card-head">
            <span class="mobile-filename">{{ row.fileName }}</span>
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </div>
          <div class="mobile-card-meta">
            <span>{{ formatSize(row.fileSize) }}</span>
            <span>{{ row.scopeType }}</span>
            <span>{{ row.chunkCount ?? '—' }} 切片</span>
          </div>
          <div class="mobile-card-actions">
            <el-button v-if="row.status === 'SUCCESS'" type="primary" link size="small" @click="openChunkDetail(row.taskId)">
              查看切片
            </el-button>
            <el-button type="danger" link size="small" @click="onDelete(row)">删除</el-button>
          </div>
        </div>
        <el-empty v-if="!loading && records.length === 0" description="暂无上传记录" />
      </div>

      <div class="kb-pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
        />
      </div>
      </div>
    </div>
  </div>

  <ChunkDetailPanel
    :visible="chunkPanelVisible"
    :task-id="activeChunkTaskId"
    :focus-chunk-index="focusChunkIndex"
    @close="closeChunkDetail"
    @open-card="openCardFromChunk"
  />
  </div>
</template>

<style scoped>
.kb-page {
  padding-top: 48px;
  min-height: 100vh;
  position: relative;
  z-index: 1;
  overflow-y: auto;
}

.kb-container {
  max-width: 960px;
  margin: 24px auto;
  padding: 0;
  background: var(--bg-elevated);
  border-radius: var(--radius);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-md);
  overflow: hidden;
}

.kb-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 24px 28px 20px;
  background: var(--bg-surface);
}

.kb-title {
  flex: 1;
  margin: 0;
  font-size: 1.4rem;
  font-weight: 700;
  color: var(--text-primary);
}

.kb-header .icon-btn {
  border-color: var(--border-bright);
  color: var(--text-secondary);
}

.kb-header .icon-btn:hover {
  border-color: var(--border-bright);
  color: var(--text-primary);
  background: var(--bg-hover);
}

.icon-btn {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: color var(--transition-fast), border-color var(--transition-fast), background var(--transition-fast);
}

.icon-btn:hover {
  color: var(--text-primary);
  border-color: var(--border-bright);
}

/* 内容区 */
.kb-content {
  padding: 0 28px 28px;
}

.kb-upload {
  margin-bottom: 20px;
  margin-top: 4px;
  padding: 16px 20px;
  border-radius: var(--radius-lg);
  border: 1px dashed var(--border-bright);
  background: var(--bg-hover);
  transition: border-color var(--transition-fast), background var(--transition-fast);
}

.kb-upload:hover {
  border-color: var(--border-bright);
  background: var(--bg-active);
}

.hint {
  font-size: 12px;
  color: var(--text-secondary);
  margin: 8px 0 0;
}

.kb-tabs {
  margin-bottom: 12px;
}

.kb-table {
  border-radius: var(--radius-lg);
  overflow: hidden;
  border: 1px solid var(--border);
  background: var(--bg-elevated);
}

.kb-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.kb-mobile-card {
  padding: 12px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
  margin-bottom: 10px;
}

.mobile-card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.mobile-filename {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mobile-card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 8px;
  color: var(--text-secondary);
  font-size: 12px;
}

.mobile-card-actions {
  display: flex;
  gap: 8px;
}

</style>
