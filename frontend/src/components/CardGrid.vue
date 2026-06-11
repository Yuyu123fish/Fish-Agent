<script setup lang="ts">
import { renderMarkdown } from '@/utils/markdown'
import { relativePastTime } from '@/utils/time'
import type { CardListItem } from '@/api/card'

defineProps<{
  cards: CardListItem[]
  selectedIds?: number[]
  expandedId: number | null
}>()

const emit = defineEmits<{
  select: [card: CardListItem]
  toggle: [card: CardListItem, checked: boolean]
  expand: [cardId: number | null]
  action: [cardId: number, action: 'confirm' | 'edit' | 'reject' | 'delete']
  keywordClick: [keyword: string]
}>()

function preview(text: string): string {
  const raw = text?.trim() || ''
  return raw.length > 80 ? `${raw.slice(0, 80)}...` : raw
}

function typeLabel(type: string): string {
  return type === 'topic' ? '主题' : '概念'
}

function visibleKeywords(card: CardListItem): string[] {
  return (card.keywords ?? []).slice(0, 3)
}

function isSelected(card: CardListItem, selectedIds?: number[]): boolean {
  return (selectedIds ?? []).includes(card.id)
}

function handleToggle(card: CardListItem, checked: string | number | boolean) {
  emit('toggle', card, Boolean(checked))
}

function isReviewDue(nextAt?: string | null): boolean {
  if (!nextAt) return false
  return new Date(nextAt) <= new Date()
}

function reviewHintText(nextAt?: string | null): string {
  if (!nextAt) return '尚未复习'
  const diff = Math.ceil((new Date(nextAt).getTime() - Date.now()) / 86400000)
  if (diff <= 0) return '复习到期'
  if (diff === 1) return '明天复习'
  if (diff <= 7) return `${diff}天后复习`
  return `${Math.ceil(diff / 7)}周后复习`
}

function renderedPreview(text: string): string {
  const raw = text?.trim() || ''
  const truncated = raw.length > 300 ? `${raw.slice(0, 300)}...` : raw
  return renderMarkdown(truncated)
}
</script>

<template>
  <div class="card-grid">
    <article
      v-for="card in cards"
      :key="card.id"
      tabindex="0"
      class="k-card"
      @keydown.enter="emit('expand', expandedId === card.id ? null : card.id)"
      @keydown.escape="emit('expand', null)"
      :class="{
        pending: card.status === 'pending',
        confirmed: card.status === 'confirmed',
        expanded: expandedId === card.id,
        'review-due': isReviewDue(card.reviewNextAt)
      }"
    >
      <el-checkbox
        class="card-check"
        :model-value="isSelected(card, selectedIds)"
        @click.stop
        @change="handleToggle(card, $event)"
      />

      <div class="card-body" @click="emit('expand', card.id)">
        <div class="card-head">
          <span class="status-dot" />
          <h3>{{ card.title }}</h3>
          <span class="type-badge">{{ typeLabel(card.cardType) }}</span>
        </div>
        <p class="preview">{{ preview(card.contentPreview) }}</p>
        <div class="keywords">
          <el-tag
            v-for="kw in visibleKeywords(card)"
            :key="kw"
            size="small"
            effect="plain"
            @click.stop="emit('keywordClick', kw)"
          >
            {{ kw }}
          </el-tag>
        </div>
        <div class="meta-row">
          <span v-if="card.groupName" class="group">{{ card.groupName }}</span>
          <span v-else class="group muted">未分组</span>
          <span>{{ card.relationCount }} 关联</span>
          <span class="time">{{ relativePastTime(card.createdAt) }}</span>
        </div>
        <div class="review-hint" :class="{ urgent: isReviewDue(card.reviewNextAt) }">
          {{ reviewHintText(card.reviewNextAt) }}
        </div>
      </div>

      <Transition name="collapse">
        <div v-if="expandedId === card.id" class="card-expanded">
          <div class="card-head">
            <span class="status-dot" />
            <h3 class="clickable" @click="emit('select', card)">{{ card.title }}</h3>
            <span class="type-badge">{{ typeLabel(card.cardType) }}</span>
            <button class="collapse-btn" type="button" @click.stop="emit('expand', null)">收起 ▲</button>
          </div>

          <section class="markdown-body expanded-content" v-html="renderedPreview(card.contentPreview)" />

          <div class="keywords">
            <el-tag
              v-for="kw in card.keywords ?? []"
              :key="kw"
              size="small"
              effect="plain"
              @click.stop="emit('keywordClick', kw)"
            >
              {{ kw }}
            </el-tag>
          </div>

          <div class="expanded-meta">
            <span>{{ card.groupName || '未分组' }}</span>
            <span>{{ card.relationCount }} 关联</span>
            <span v-if="card.reviewCount">复习 {{ card.reviewCount }} 次</span>
            <span>{{ reviewHintText(card.reviewNextAt) }}</span>
          </div>

          <div class="expanded-actions">
            <button v-if="card.status === 'pending'" class="confirm-btn" type="button" @click.stop="emit('action', card.id, 'confirm')">确认</button>
            <button class="edit-btn" type="button" @click.stop="emit('action', card.id, 'edit')">编辑</button>
            <button v-if="card.status === 'pending'" class="warn-btn" type="button" @click.stop="emit('action', card.id, 'reject')">拒绝</button>
            <button class="danger-btn" type="button" @click.stop="emit('action', card.id, 'delete')">删除</button>
          </div>
        </div>
      </Transition>
    </article>
  </div>
</template>

<style scoped>
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.k-card {
  position: relative;
  min-height: 190px;
  border-radius: var(--radius);
  border: 1px solid var(--border);
  background: var(--bg-elevated);
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
  transition: transform var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.card-body {
  padding: 16px;
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
}

.card-body {
  cursor: pointer;
}

.card-check {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 2;
}

.k-card .card-head {
  padding-left: 22px;
}

.k-card:hover {
  transform: translateY(-2px);
  border-color: var(--border-bright);
  box-shadow: var(--shadow-md);
}

.k-card.pending {
  border-left: 2px solid var(--status-warning);
}

.k-card.confirmed {
  border-left: 2px solid var(--status-ok);
}

.k-card.expanded {
  z-index: 5;
  min-height: 190px;
}

.card-expanded {
  position: absolute;
  top: 100%;
  left: -8px;
  right: -8px;
  z-index: 5;
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 420px;
  padding: 16px;
  overflow-y: auto;
  border: 1px solid var(--border-bright);
  border-radius: var(--radius);
  background: var(--bg-elevated);
  box-shadow: var(--shadow-lg);
}

@keyframes review-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(143, 135, 107, 0);
  }
  50% {
    box-shadow: 0 0 0 4px rgba(143, 135, 107, 0.22);
  }
}

.k-card.review-due {
  animation: review-pulse 2s ease-in-out infinite;
}

.card-head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-top: 7px;
  background: var(--status-warning);
}

.confirmed .status-dot {
  background: var(--status-ok);
}

h3 {
  flex: 1;
  margin: 0;
  color: var(--text-primary);
  font-size: 16px;
  line-height: 1.45;
}

.clickable {
  cursor: pointer;
}

.clickable:hover {
  text-decoration: underline;
  text-underline-offset: 3px;
}

.type-badge {
  flex-shrink: 0;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: var(--bg-hover);
  border: 1px solid var(--border);
}

.preview {
  margin: 0;
  color: var(--text);
  line-height: 1.7;
  font-size: 13px;
  flex: 1;
}

.meta-row,
.expanded-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: var(--text-secondary);
}

.group {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.muted {
  color: var(--text-muted);
}

.keywords {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 24px;
}

.keywords :deep(.el-tag) {
  cursor: pointer;
}

.review-hint {
  font-size: 12px;
  color: var(--text-secondary);
}

.review-hint.urgent {
  color: var(--status-warning);
  font-weight: 600;
}

.expanded-content {
  max-height: 220px;
  overflow-y: auto;
  padding: 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  color: var(--text);
  background: var(--bg-sunken);
}

.collapse-btn,
.confirm-btn,
.edit-btn,
.warn-btn,
.danger-btn {
  height: 30px;
  padding: 0 12px;
  border-radius: var(--radius-sm);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.collapse-btn,
.edit-btn {
  border: 1px solid var(--border);
  color: var(--text-primary);
  background: transparent;
}

.expanded-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
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
</style>
