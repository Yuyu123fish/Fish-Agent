<script setup lang="ts">
/**
 * 知识库管理页：上传、任务列表、删除。
 * 上传逻辑复用 {@link KnowledgeUpload}；列表与删除通过 {@link knowledgeApi}。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import * as knowledgeApi from '@/api/knowledge'
import type { DocumentMetadataItem } from '@/api/knowledge'
import KnowledgeUpload from '@/components/KnowledgeUpload.vue'

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
    page.value = 1 // watch 触发 loadList
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
  <div class="kb-page">
    <header class="kb-header">
      <el-button :icon="ArrowLeft" text @click="goChat">返回对话</el-button>
      <h1 class="kb-title">知识库</h1>
      <el-button :icon="Refresh" circle @click="loadList" />
    </header>

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
</template>

<style scoped>
.kb-page {
  max-width: 960px;
  margin: 0 auto;
  padding: 16px 20px 32px;
  min-height: 100vh;
  background: var(--bg-page, #f9fafb);
}

.kb-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.kb-title {
  flex: 1;
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.kb-upload {
  background: var(--bg-main, #fff);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 12px;
  border: 1px solid var(--border);
}

.hint {
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
  margin: 8px 0 0;
}

.kb-tabs {
  margin-bottom: 8px;
}

.kb-table {
  border-radius: 8px;
  overflow: hidden;
}

.kb-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
