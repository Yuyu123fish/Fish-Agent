<script setup lang="ts">
import type { CardListItem } from '@/api/card'

defineProps<{
  cards: CardListItem[]
  selectedIds?: number[]
}>()

const emit = defineEmits<{
  select: [card: CardListItem]
  toggle: [card: CardListItem, checked: boolean]
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
</script>

<template>
  <div class="card-grid">
    <article
      v-for="card in cards"
      :key="card.id"
      class="k-card"
      :class="{ pending: card.status === 'pending', confirmed: card.status === 'confirmed' }"
      @click="emit('select', card)"
    >
      <el-checkbox
        class="card-check"
        :model-value="isSelected(card, selectedIds)"
        @click.stop
        @change="handleToggle(card, $event)"
      />
      <div class="card-head">
        <span class="status-dot" />
        <h3>{{ card.title }}</h3>
        <span class="type-badge">{{ typeLabel(card.cardType) }}</span>
      </div>
      <p class="preview">{{ preview(card.contentPreview) }}</p>
      <div class="meta-row">
        <span v-if="card.groupName" class="group">{{ card.groupName }}</span>
        <span v-else class="group muted">未分组</span>
        <span>{{ card.relationCount }} 关联</span>
      </div>
      <div class="keywords">
        <el-tag v-for="kw in visibleKeywords(card)" :key="kw" size="small" effect="plain">
          {{ kw }}
        </el-tag>
      </div>
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
  padding: 16px;
  border-radius: var(--radius);
  border: 1px solid var(--border);
  background: var(--bg-elevated);
  box-shadow: var(--shadow-sm);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: transform var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast);
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
  font-size: 16px;
  line-height: 1.45;
  color: var(--text-primary);
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

.meta-row {
  display: flex;
  justify-content: space-between;
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
</style>
