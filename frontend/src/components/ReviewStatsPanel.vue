<script setup lang="ts">
import { computed } from 'vue'
import type { ReviewStatsResponse } from '@/api/card'

const props = defineProps<{
  stats: ReviewStatsResponse
}>()

const emit = defineEmits<{
  close: []
}>()

const unreviewed = computed(() => Math.max(0, props.stats.totalCards - props.stats.mastered - props.stats.learning))
const masteryItems = computed(() => {
  const total = props.stats.totalCards || 1
  return [
    { label: '已掌握', count: props.stats.mastered, percent: Math.round((props.stats.mastered / total) * 100), cls: 'mastered' },
    { label: '学习中', count: props.stats.learning, percent: Math.round((props.stats.learning / total) * 100), cls: 'learning' },
    { label: '未复习', count: unreviewed.value, percent: Math.round((unreviewed.value / total) * 100), cls: 'unreviewed' }
  ]
})

const calendarDays = computed(() => {
  const days: Array<{ date: string; count: number }> = []
  const now = new Date()
  for (let i = 27; i >= 0; i--) {
    const d = new Date(now)
    d.setDate(d.getDate() - i)
    const key = d.toISOString().slice(0, 10)
    days.push({ date: key, count: props.stats.reviewCalendar?.[key] ?? 0 })
  }
  return days
})

function heatClass(count: number): string {
  if (count === 0) return 'heat-0'
  if (count <= 2) return 'heat-1'
  if (count <= 5) return 'heat-2'
  if (count <= 10) return 'heat-3'
  return 'heat-4'
}
</script>

<template>
  <section class="stats-panel">
    <header class="stats-head">
      <h2>复习统计</h2>
      <button class="close-btn" type="button" @click="emit('close')">×</button>
    </header>

    <section class="mastery-section">
      <div v-for="item in masteryItems" :key="item.label" class="mastery-row">
        <span class="label">{{ item.label }}</span>
        <div class="bar-track">
          <div class="bar-fill" :class="item.cls" :style="{ width: `${item.percent}%` }" />
        </div>
        <span class="count">{{ item.count }} ({{ item.percent }}%)</span>
      </div>
    </section>

    <section class="calendar-section">
      <h3>近 28 天</h3>
      <div class="calendar-grid">
        <div
          v-for="day in calendarDays"
          :key="day.date"
          class="calendar-cell"
          :class="heatClass(day.count)"
          :title="`${day.date}: ${day.count} 张`"
        />
      </div>
    </section>

    <section class="summary-section">
      <div class="summary-item">连续学习 {{ stats.streakDays }} 天</div>
      <div class="summary-item">今日待复习 {{ stats.dueToday }} 张</div>
    </section>
  </section>
</template>

<style scoped>
.stats-panel {
  width: min(620px, 100%);
  margin: 16px auto 0;
  padding: 16px;
  border-radius: var(--radius);
  border: 1px solid var(--border);
  color: var(--text);
  background: var(--bg-surface);
  box-shadow: var(--shadow-md);
}

.stats-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

h2,
h3 {
  margin: 0;
  color: var(--text-primary);
}

h2 {
  font-size: 18px;
}

h3 {
  font-size: 14px;
  margin-bottom: 10px;
}

.close-btn {
  width: 30px;
  height: 30px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: transparent;
  cursor: pointer;
}

.mastery-section {
  display: grid;
  gap: 10px;
}

.mastery-row {
  display: grid;
  grid-template-columns: 64px 1fr 88px;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}

.label,
.count {
  color: var(--text-secondary);
}

.bar-track {
  height: 8px;
  border-radius: 999px;
  overflow: hidden;
  background: var(--bg-hover);
}

.bar-fill {
  height: 100%;
  border-radius: inherit;
}

.bar-fill.mastered {
  background: var(--status-ok);
}

.bar-fill.learning {
  background: var(--status-warning);
}

.bar-fill.unreviewed {
  background: var(--text-muted);
}

.calendar-section {
  margin-top: 18px;
}

.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 4px;
}

.calendar-cell {
  aspect-ratio: 1;
  border-radius: 3px;
}

.heat-0 {
  background: var(--bg-hover);
}

.heat-1 {
  background: rgba(107, 143, 113, 0.25);
}

.heat-2 {
  background: rgba(107, 143, 113, 0.5);
}

.heat-3 {
  background: rgba(107, 143, 113, 0.75);
}

.heat-4 {
  background: var(--status-ok);
}

.summary-section {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 16px;
}

.summary-item {
  padding: 10px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  color: var(--text-primary);
  background: var(--bg-hover);
  text-align: center;
}
</style>
