<script setup lang="ts">
/**
 * 切片与知识卡片的关联列表。
 *
 * 组件只负责展示和抛出打开事件，不直接依赖路由，方便在切片面板、卡片详情和后续更多入口复用。
 */
export interface ChunkCardLinkItem {
  key: string | number
  title: string
  description?: string
  meta?: string
  similarity?: number
}

defineProps<{
  items: ChunkCardLinkItem[]
  emptyText?: string
  actionText?: string
}>()

const emit = defineEmits<{
  open: [item: ChunkCardLinkItem]
}>()

function similarityText(value?: number): string {
  if (value == null) return ''
  return `${Math.round(value * 100)}%`
}
</script>

<template>
  <div class="link-list">
    <div v-if="items.length === 0" class="empty">{{ emptyText || '暂无关联' }}</div>
    <article v-for="item in items" v-else :key="item.key" class="link-item">
      <div class="link-main">
        <strong>{{ item.title }}</strong>
        <p v-if="item.description">{{ item.description }}</p>
        <span v-if="item.meta" class="meta">{{ item.meta }}</span>
      </div>
      <div class="link-side">
        <span v-if="item.similarity != null" class="score">{{ similarityText(item.similarity) }}</span>
        <button class="open-btn" type="button" @click="emit('open', item)">
          {{ actionText || '查看' }}
        </button>
      </div>
    </article>
  </div>
</template>

<style scoped>
.link-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.empty {
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: rgba(20, 20, 35, 0.36);
  font-size: 13px;
}

.link-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: rgba(20, 20, 35, 0.48);
}

.link-main {
  min-width: 0;
}

.link-main strong {
  display: block;
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.45;
}

.link-main p {
  margin: 5px 0;
  color: var(--text);
  font-size: 12px;
  line-height: 1.65;
}

.meta {
  color: var(--text-secondary);
  font-size: 12px;
}

.link-side {
  flex-shrink: 0;
  display: flex;
  align-items: flex-end;
  flex-direction: column;
  gap: 8px;
}

.score {
  color: #34d399;
  font-size: 12px;
}

.open-btn {
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--primary-light);
  background: transparent;
  cursor: pointer;
}

.open-btn:hover {
  border-color: var(--primary);
  background: rgba(99, 102, 241, 0.1);
}
</style>
