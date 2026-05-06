<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import * as knowledgeApi from '@/api/knowledge'

const props = defineProps<{
  /** 与侧栏一致：流式生成中禁用上传 */
  disabled?: boolean
}>()

const emit = defineEmits<{
  /** 解析成功（任务 SUCCESS），供知识库页刷新列表 */
  ingestSuccess: []
}>()

const auth = useAuthStore()
const { role } = storeToRefs(auth)

const isAdmin = computed(() => (role.value ?? '').toUpperCase() === 'ADMIN')

/** 小于等于此大小走直传（服务端流式写入，避免 JVM 堆占用） */
const SMALL_FILE_LIMIT_BYTES = 1 * 1024 * 1024
/** MinIO compose：中间分片须 ≥5MB；最后一个分片可更小 */
const CHUNK_SIZE_BYTES = 5 * 1024 * 1024

const statusLine = ref('')
let pollTimer: ReturnType<typeof setInterval> | null = null

function clearPoll() {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onUnmounted(() => clearPoll())

async function startPolling(taskId: string) {
  clearPoll()
  statusLine.value = '已提交，等待解析…'
  pollTimer = setInterval(async () => {
    try {
      const s = await knowledgeApi.pollTaskStatus(taskId)
      if (s.status === 'SUCCESS') {
        clearPoll()
        statusLine.value = ''
        ElMessage.success('知识库解析完成')
        emit('ingestSuccess')
      } else if (s.status === 'FAILED') {
        clearPoll()
        statusLine.value = ''
        ElMessage.error(s.errorMsg?.trim() || '解析失败')
      } else if (s.status === 'PROCESSING') {
        statusLine.value = '正在解析…'
      } else {
        statusLine.value = '排队等待处理…'
      }
    } catch (e) {
      clearPoll()
      statusLine.value = ''
      ElMessage.error(e instanceof Error ? e.message : '查询任务状态失败')
    }
  }, 2000)
}

async function uploadSmallFile(file: File, scope: 'private' | 'public') {
  const res =
    scope === 'public'
      ? await knowledgeApi.uploadAdminKnowledge(file)
      : await knowledgeApi.uploadUserKnowledge(file)
  ElMessage.success('文件已上传，正在排队解析')
  await startPolling(res.taskId)
}

async function uploadLargeFile(file: File, scope: 'private' | 'public') {
  const ct = file.type || 'application/octet-stream'
  const init = await knowledgeApi.initMultipartUpload(file.name, file.size, ct, scope)

  const totalParts = Math.max(1, Math.ceil(file.size / CHUNK_SIZE_BYTES))
  const parts: knowledgeApi.MultipartPartInfo[] = []

  try {
    for (let i = 0; i < totalParts; i++) {
      const start = i * CHUNK_SIZE_BYTES
      const end = Math.min(start + CHUNK_SIZE_BYTES, file.size)
      const blob = file.slice(start, end)
      statusLine.value = `上传分片 ${i + 1}/${totalParts}…`
      const { etag } = await knowledgeApi.uploadChunk(
        init.taskId,
        init.uploadId,
        init.minioPath,
        i + 1,
        blob
      )
      parts.push({ partNumber: i + 1, etag })
    }

    statusLine.value = '正在合并文件并入队…'
    await knowledgeApi.completeMultipartUpload(init.taskId, init.uploadId, init.minioPath, parts)
    ElMessage.success('文件已上传，正在排队解析')
    await startPolling(init.taskId)
  } catch (e) {
    try {
      await knowledgeApi.abortMultipartUpload(init.taskId, init.uploadId, init.minioPath)
    } catch {
      /* ignore */
    }
    throw e
  }
}

async function doUpload(file: File, scope: 'private' | 'public') {
  if (props.disabled) return
  try {
    if (file.size <= SMALL_FILE_LIMIT_BYTES) {
      await uploadSmallFile(file, scope)
    } else {
      await uploadLargeFile(file, scope)
    }
  } catch (e) {
    statusLine.value = ''
    ElMessage.error(e instanceof Error ? e.message : '上传失败')
  }
}

function onPrivateFileChange(uploadFile: UploadFile) {
  const raw = uploadFile.raw
  if (raw) {
    void doUpload(raw, 'private')
  }
}

function onPublicFileChange(uploadFile: UploadFile) {
  const raw = uploadFile.raw
  if (raw) {
    void doUpload(raw, 'public')
  }
}
</script>

<template>
  <div class="kb-wrap">
    <div class="kb-row">
      <el-upload
        :show-file-list="false"
        :auto-upload="false"
        :disabled="disabled"
        accept="*/*"
        :on-change="onPrivateFileChange"
      >
        <el-button size="small" :icon="Upload" :disabled="disabled" plain>
          上传知识库
        </el-button>
      </el-upload>
      <el-upload
        v-if="isAdmin"
        :show-file-list="false"
        :auto-upload="false"
        :disabled="disabled"
        accept="*/*"
        :on-change="onPublicFileChange"
      >
        <el-button size="small" type="primary" :disabled="disabled" plain> 上传公共知识库 </el-button>
      </el-upload>
    </div>
    <div v-if="statusLine" class="kb-status">{{ statusLine }}</div>
  </div>
</template>

<style scoped>
.kb-wrap {
  padding: 8px 12px 4px;
  border-bottom: 1px solid var(--border);
}

.kb-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.kb-status {
  font-size: 11px;
  color: var(--text-secondary, #6b7280);
  margin-top: 6px;
  min-height: 16px;
}
</style>
