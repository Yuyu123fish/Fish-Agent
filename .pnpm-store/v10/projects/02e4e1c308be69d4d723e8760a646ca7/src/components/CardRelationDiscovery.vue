<script setup lang="ts">
/**
 * 发现关联弹窗：负责调用发现接口、展示建议和确认选中关系。
 */
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Link, Refresh } from '@element-plus/icons-vue'
import {
  confirmDiscoveredRelations,
  discoverRelations,
  migrateKeywords,
  type RelationSuggestion
} from '@/api/card'

const props = defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  close: []
  confirmed: []
}>()

const loading = ref(false)
const confirming = ref(false)
const migrating = ref(false)
const suggestions = ref<RelationSuggestion[]>([])
const selectedKeys = ref<string[]>([])

watch(
  () => props.visible,
  (visible) => {
    if (visible) void loadSuggestions()
  }
)

function keyOf(item: RelationSuggestion): string {
  return `${item.fromCardId}:${item.toCardId}:${item.suggestedType}`
}

async function loadSuggestions() {
  loading.value = true
  try {
    const result = await discoverRelations()
    suggestions.value = result.suggestions
    selectedKeys.value = result.suggestions.map(keyOf)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发现关联失败')
  } finally {
    loading.value = false
  }
}

async function handleMigrate() {
  migrating.value = true
  try {
    const result = await migrateKeywords()
    ElMessage.success(`关键词迁移完成：${result.migrated} 张卡片`)
    await loadSuggestions()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '迁移关键词失败')
  } finally {
    migrating.value = false
  }
}

async function confirmSelected() {
  const selected = suggestions.value.filter((item) => selectedKeys.value.includes(keyOf(item)))
  if (selected.length === 0) {
    ElMessage.warning('请至少选择一条关联')
    return
  }
  confirming.value = true
  try {
    await confirmDiscoveredRelations(
      selected.map((item) => ({
        fromCardId: item.fromCardId,
        toCardId: item.toCardId,
        relationType: item.suggestedType
      }))
    )
    ElMessage.success(`已确认 ${selected.length} 条关联`)
    emit('confirmed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '确认关联失败')
  } finally {
    confirming.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    width="720px"
    destroy-on-close
    class="relation-discovery-dialog"
    modal-class="relation-discovery-overlay"
    @close="emit('close')"
  >
    <template #header>
      <div class="dialog-head">
        <el-icon><Link /></el-icon>
        <span>发现关联</span>
      </div>
    </template>

    <div class="tools">
      <button class="ghost-btn" type="button" :disabled="loading" @click="loadSuggestions">
        <el-icon><Refresh /></el-icon>
        重新发现
      </button>
      <button class="ghost-btn" type="button" :disabled="migrating" @click="handleMigrate">
        {{ migrating ? '迁移中…' : '迁移历史关键词' }}
      </button>
    </div>

    <div v-loading="loading" class="suggestions">
      <div v-if="!loading && suggestions.length === 0" class="empty">
        当前没有发现新的关联
      </div>

      <article v-for="item in suggestions" :key="keyOf(item)" class="suggestion">
        <el-checkbox v-model="selectedKeys" :value="keyOf(item)" />
        <div class="suggestion-main">
          <div class="pair">
            <strong>{{ item.fromTitle }}</strong>
            <span>相关</span>
            <strong>{{ item.toTitle }}</strong>
          </div>
          <div class="reasons">
            <span v-for="reason in item.reasons" :key="reason">{{ reason }}</span>
          </div>
        </div>
        <div class="score">{{ item.confidence.toFixed(2) }}</div>
      </article>
    </div>

    <template #footer>
      <button class="ghost-btn" type="button" @click="emit('close')">取消</button>
      <button class="primary-btn" type="button" :disabled="confirming || selectedKeys.length === 0" @click="confirmSelected">
        {{ confirming ? '确认中…' : `确认选中 (${selectedKeys.length})` }}
      </button>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-head {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-primary);
  font-weight: 700;
}

.tools {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-bottom: 12px;
}

.suggestions {
  min-height: 180px;
  max-height: 56vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.empty {
  height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
}

.suggestion {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: flex-start;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-hover);
}

.suggestion-main {
  min-width: 0;
}

.pair {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  color: var(--text-primary);
  line-height: 1.5;
}

.pair span {
  color: var(--text-secondary);
  font-size: 12px;
}

.reasons {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.reasons span {
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: var(--bg-active);
  font-size: 12px;
}

.score {
  min-width: 48px;
  text-align: right;
  color: var(--text-primary);
  font-weight: 700;
}

.ghost-btn,
.primary-btn {
  height: 34px;
  padding: 0 16px;
  border-radius: var(--radius-sm);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.ghost-btn {
  border: 1px solid var(--border);
  color: var(--text-primary);
  background: transparent;
}

.primary-btn {
  border: 0;
  color: var(--bg-base);
  background: var(--accent);
}

.ghost-btn:disabled,
.primary-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
</style>

<!-- unscoped：el-dialog 挂载在 body 下，弹窗外壳需要用全局选择器覆盖。 -->
<style>
.relation-discovery-dialog.el-dialog {
  background: var(--bg-surface) !important;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
}

.relation-discovery-dialog .el-dialog__header {
  border-bottom: 1px solid var(--border);
  padding: 16px 20px;
  margin-right: 0;
}

.relation-discovery-dialog .el-dialog__body {
  padding: 20px;
  color: var(--text);
}

.relation-discovery-dialog .el-dialog__footer {
  border-top: 1px solid var(--border);
  padding: 12px 20px;
}

.relation-discovery-dialog .el-dialog__headerbtn .el-dialog__close {
  color: var(--text-secondary);
}

.relation-discovery-overlay {
  background-color: rgba(0, 0, 0, 0.6);
}
</style>
