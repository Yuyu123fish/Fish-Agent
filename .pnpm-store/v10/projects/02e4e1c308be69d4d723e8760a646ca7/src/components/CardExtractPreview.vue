<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { marked } from 'marked'
import { ElMessage } from 'element-plus'
import { Edit, Link, Tickets } from '@element-plus/icons-vue'
import {
  batchConfirmCards,
  updateCard,
  type CardDetail,
  type ExtractResult
} from '@/api/card'

const props = defineProps<{
  visible: boolean
  result: ExtractResult | null
}>()

const emit = defineEmits<{
  close: []
  confirmed: []
}>()

interface EditableCard {
  id: number
  title: string
  content: string
  keywordsText: string
  cardType: 'concept' | 'topic'
  groupName: string
  expanded: boolean
}

const selectedIds = ref<number[]>([])
const drafts = ref<EditableCard[]>([])
const saving = ref(false)

const relationCount = computed(() => props.result?.relations?.length ?? 0)
const selectedCount = computed(() => selectedIds.value.length)

watch(
  () => [props.visible, props.result] as const,
  () => {
    if (!props.visible || !props.result) return
    drafts.value = props.result.cards.map(toEditable)
    selectedIds.value = props.result.cards.map((c) => c.id)
  },
  { immediate: true }
)

function toEditable(card: CardDetail): EditableCard {
  return {
    id: card.id,
    title: card.title,
    content: card.content,
    keywordsText: (card.keywords ?? []).join('，'),
    cardType: card.cardType ?? 'concept',
    groupName: card.groupName ?? '',
    expanded: false
  }
}

function keywordsOf(text: string): string[] {
  return text
    .split(/[，,、\s]+/)
    .map((x) => x.trim())
    .filter(Boolean)
    .slice(0, 8)
}

async function confirmSelected() {
  if (selectedIds.value.length === 0) {
    ElMessage.warning('请至少选择一张卡片')
    return
  }
  saving.value = true
  try {
    const selected = drafts.value.filter((c) => selectedIds.value.includes(c.id))
    // 先保存行内编辑，再执行确认，避免用户修改只停留在前端预览里。
    for (const card of selected) {
      await updateCard(card.id, {
        title: card.title.trim(),
        content: card.content.trim(),
        keywords: keywordsOf(card.keywordsText),
        cardType: card.cardType,
        groupName: card.groupName.trim() || null
      })
    }
    await batchConfirmCards(selectedIds.value)
    ElMessage.success(`已确认 ${selectedIds.value.length} 张知识卡片`)
    emit('confirmed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '确认失败')
  } finally {
    saving.value = false
  }
}

function typeLabel(type: string): string {
  return type === 'topic' ? '主题' : '概念'
}

function renderMd(text: string): string {
  return text ? (marked.parse(text) as string) : ''
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    width="720px"
    class="extract-dialog"
    modal-class="extract-overlay"
    append-to-body
    destroy-on-close
    @close="emit('close')"
  >
    <template #header>
      <div class="dialog-head">
        <el-icon><Tickets /></el-icon>
        <span>AI 提取了 {{ result?.extractedCount ?? 0 }} 张知识卡片</span>
      </div>
    </template>

    <div v-if="drafts.length === 0" class="empty">本次没有提取到可用卡片</div>
    <div v-else class="extract-list">
      <article v-for="card in drafts" :key="card.id" class="extract-item">
        <div class="item-row">
          <el-checkbox v-model="selectedIds" :value="card.id" />
          <div class="item-main">
            <strong>{{ card.title }}</strong>
            <div class="item-meta">
              <span>{{ typeLabel(card.cardType) }}</span>
              <span>{{ card.groupName || '未分组' }}</span>
            </div>
          </div>
          <button class="icon-text" type="button" @click="card.expanded = !card.expanded">
            <el-icon><Edit /></el-icon>
            编辑
          </button>
        </div>

        <div v-if="!card.expanded" class="item-preview markdown-body" v-html="renderMd(card.content)" />
        <div v-if="card.expanded" class="editor">
          <el-input v-model="card.title" maxlength="200" placeholder="标题" />
          <el-input
            v-model="card.content"
            type="textarea"
            :autosize="{ minRows: 4, maxRows: 8 }"
            placeholder="卡片内容"
          />
          <div class="editor-row">
            <el-input v-model="card.keywordsText" placeholder="关键词，用逗号分隔" />
            <el-select v-model="card.cardType">
              <el-option label="概念" value="concept" />
              <el-option label="主题" value="topic" />
            </el-select>
          </div>
          <el-input v-model="card.groupName" placeholder="分组" />
        </div>
      </article>
    </div>

    <div class="relation-line">
      <el-icon><Link /></el-icon>
      检测到 {{ relationCount }} 条关联关系
    </div>

    <template #footer>
      <button class="ghost-btn" type="button" @click="emit('close')">稍后处理</button>
      <button class="primary-btn" type="button" :disabled="saving || selectedCount === 0" @click="confirmSelected">
        {{ saving ? '确认中…' : `确认选中 (${selectedCount})` }}
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
  font-size: 15px;
}

.empty {
  padding: 34px;
  text-align: center;
  color: var(--text-secondary);
}

.extract-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 54vh;
  overflow-y: auto;
  padding-right: 4px;
}

.extract-item {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-hover);
  transition: border-color var(--transition-fast);
}

.extract-item:hover {
  border-color: var(--border-bright);
}

.item-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
}

.item-main {
  flex: 1;
  min-width: 0;
}

.item-main strong {
  display: block;
  color: var(--text-primary);
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-meta {
  display: flex;
  gap: 10px;
  margin-top: 4px;
  color: var(--text);
  font-size: 12px;
}

.icon-text {
  height: 30px;
  padding: 0 10px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: transparent;
  cursor: pointer;
  transition: color var(--transition-fast), border-color var(--transition-fast);
}

.icon-text:hover {
  color: var(--text-primary);
  border-color: var(--border-bright);
}

.item-preview {
  padding: 0 12px 12px;
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  color: var(--text);
  max-height: 120px;
  overflow-y: auto;
  border-top: 1px solid var(--border);
  padding-top: 10px;
}

.item-preview :deep(p) {
  margin: 0 0 6px;
}

.item-preview :deep(ul),
.item-preview :deep(ol) {
  margin: 4px 0;
  padding-left: 18px;
}

.item-preview :deep(strong) {
  color: var(--text-primary);
}

.editor {
  display: grid;
  gap: 8px;
  padding: 0 12px 12px 42px;
}

.editor-row {
  display: grid;
  grid-template-columns: 1fr 120px;
  gap: 8px;
}

.relation-line {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  color: var(--text);
  font-size: 13px;
}

.ghost-btn,
.primary-btn {
  height: 34px;
  padding: 0 18px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 13px;
}

.ghost-btn {
  border: 1px solid var(--border);
  color: var(--text-secondary);
  background: transparent;
  transition: color var(--transition-fast), border-color var(--transition-fast);
}

.ghost-btn:hover {
  color: var(--text-primary);
  border-color: var(--border-bright);
}

.primary-btn {
  border: 0;
  color: var(--bg-base);
  background: var(--accent);
}

.primary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

@media (max-width: 640px) {
  .editor {
    padding-left: 12px;
  }

  .editor-row {
    grid-template-columns: 1fr;
  }
}
</style>

<!-- unscoped: el-dialog 渲染在 body 层，scoped 穿透不到 -->
<style>
.extract-dialog.el-dialog {
  background: var(--bg-surface) !important;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
}
.extract-dialog .el-dialog__header {
  border-bottom: 1px solid var(--border);
  padding: 16px 20px;
  margin-right: 0;
}
.extract-dialog .el-dialog__body {
  padding: 20px;
  color: var(--text);
}
.extract-dialog .el-dialog__footer {
  border-top: 1px solid var(--border);
  padding: 12px 20px;
}
.extract-dialog .el-dialog__headerbtn .el-dialog__close {
  color: var(--text-secondary);
}
.extract-overlay {
  background-color: rgba(0, 0, 0, 0.6);
}
</style>
