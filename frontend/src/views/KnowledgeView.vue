<script setup lang="ts">
/**
 * 知识库管理页：上传、任务列表、删除。
 * 上传逻辑复用 KnowledgeUpload，列表与删除通过 knowledgeApi。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import * as knowledgeApi from '@/api/knowledge'
import type { DocumentMetadataItem } from '@/api/knowledge'
import KnowledgeUpload from '@/components/KnowledgeUpload.vue'
import AppHeader from '@/components/AppHeader.vue'
import DrawerSidebar from '@/components/DrawerSidebar.vue'

const router = useRouter()
const auth = useAuthStore()
const { role } = storeToRefs(auth)

const isAdmin = computed(() => (role.value ?? '').toUpperCase() === 'ADMIN')

const activeTab = ref<'mine' | 'all'>('mine')
const loading = ref(false)
const records = ref<DocumentMetadataItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

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

onMounted(() => void loadList())
watch([page, pageSize], () => void loadList())

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
</script>

<template>
  <DrawerSidebar />
  <AppHeader :show-back="true" @back="goChat" />
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

      <el-table v-loading="loading" :data="records" stripe class="kb-table" empty-text="暂无上传记录">
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
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button type="danger" link size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

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
  background: var(--bg-glass);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-radius: 20px;
  border: 1px solid var(--border);
  box-shadow: 0 4px 24px rgba(99, 102, 241, 0.06), var(--shadow-md);
  overflow: hidden;
}

/* 顶部渐变 Banner 区 */
.kb-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 24px 28px 20px;
  background: var(--gradient-brand);
  position: relative;
}

.kb-header::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 40px;
  background: linear-gradient(to top, var(--bg-glass), transparent);
  pointer-events: none;
}

.kb-title {
  flex: 1;
  margin: 0;
  font-size: 1.4rem;
  font-weight: 700;
  color: #ffffff;
}

.kb-header .icon-btn {
  border-color: rgba(255, 255, 255, 0.3);
  color: rgba(255, 255, 255, 0.85);
}

.kb-header .icon-btn:hover {
  border-color: rgba(255, 255, 255, 0.6);
  color: #ffffff;
  background: rgba(255, 255, 255, 0.15);
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
  color: var(--primary-light);
  border-color: var(--primary);
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
  transition: border-color var(--transition-fast), background var(--transition-fast), box-shadow var(--transition-fast);
}

.kb-upload:hover {
  border-color: var(--primary);
  background: var(--bg-active);
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.08);
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
  background: var(--bg-knowledge-table);
}

.kb-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

:deep(.el-tag--warning) {
  animation: pulse 1.5s ease-in-out infinite;
}
</style>
