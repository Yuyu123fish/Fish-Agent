<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { renderMarkdown } from '@/utils/markdown'
import { ElMessage } from 'element-plus'
import { Check, Close, RefreshLeft, Warning } from '@element-plus/icons-vue'
import ReviewStatsPanel from '@/components/ReviewStatsPanel.vue'
import {
  getReviewQueue,
  getReviewStats,
  submitReviewAnswer,
  type ReviewAnswerResponse,
  type ReviewCardVO,
  type ReviewStatsResponse
} from '@/api/card'

const props = defineProps<{
  groupId?: number | null
  groupName?: string
}>()

const emit = defineEmits<{
  close: []
  reviewed: []
}>()

const loading = ref(false)
const queue = ref<ReviewCardVO[]>([])
const total = ref(0)
const completedCount = ref(0)
const flipped = ref(false)
const lastAnswer = ref<ReviewAnswerResponse | null>(null)
const sessionStats = ref({ correct: 0, total: 0, qualitySum: 0 })
const showFullStats = ref(false)
const fullStats = ref<ReviewStatsResponse>({
  totalCards: 0,
  mastered: 0,
  learning: 0,
  dueToday: 0,
  streakDays: 0,
  reviewCalendar: {},
  weeklyActivity: [0, 0, 0, 0, 0, 0, 0]
})

const currentCard = computed(() => queue.value[0] ?? null)
const hasCards = computed(() => total.value > 0)
const isFinished = computed(() => hasCards.value && queue.value.length === 0)
const progressPercent = computed(() => {
  if (total.value === 0) return 0
  return Math.min(100, Math.round((completedCount.value / total.value) * 100))
})
const currentIndexText = computed(() => {
  if (!currentCard.value) return `${completedCount.value}/${total.value}`
  return `${Math.min(completedCount.value + 1, total.value)}/${total.value}`
})
const currentHtml = computed(() => renderMarkdown(currentCard.value?.content ?? ''))
const accuracy = computed(() => {
  if (sessionStats.value.total === 0) return 0
  return Math.round((sessionStats.value.correct / sessionStats.value.total) * 100)
})
const averageQuality = computed(() => {
  if (sessionStats.value.total === 0) return '0.0'
  return (sessionStats.value.qualitySum / sessionStats.value.total).toFixed(1)
})

onMounted(() => void loadReviewQueue())

watch(
  () => props.groupId,
  () => {
    if (queue.value.length > 0 && sessionStats.value.total > 0) return  // don't reset mid-session
    void loadReviewQueue()
  }
)

async function loadReviewQueue() {
  loading.value = true
  flipped.value = false
  completedCount.value = 0
  lastAnswer.value = null
  showFullStats.value = false
  sessionStats.value = { correct: 0, total: 0, qualitySum: 0 }
  try {
    const result = await getReviewQueue(props.groupId ?? undefined)
    queue.value = shuffle(result.cards)
    total.value = result.totalDue + result.totalNew
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载复习卡片失败')
    queue.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function flipCard() {
  if (!currentCard.value) return
  flipped.value = !flipped.value
}

async function answerCurrent(quality: 0 | 3 | 5) {
  if (!currentCard.value) return
  try {
    const [head, ...rest] = queue.value
    const result = await submitReviewAnswer(head.id, quality)
    lastAnswer.value = result
    sessionStats.value.total += 1
    sessionStats.value.qualitySum += quality
    if (quality >= 3) sessionStats.value.correct += 1

    if (quality >= 3) {
      completedCount.value += 1
      queue.value = rest
    } else {
      queue.value = [...rest, head]
    }
    flipped.value = false
    emit('reviewed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '提交评分失败')
  }
}

async function openFullStats() {
  try {
    fullStats.value = await getReviewStats()
    showFullStats.value = true
  } catch {
    ElMessage.error('加载统计失败')
  }
}

function intervalText(days: number): string {
  if (days <= 1) return '1 天后'
  if (days <= 7) return `${days} 天后`
  if (days <= 30) return `${Math.round(days / 7)} 周后`
  return `${Math.round(days / 30)} 个月后`
}

function shuffle<T>(items: T[]): T[] {
  const copy = [...items]
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[copy[i], copy[j]] = [copy[j], copy[i]]
  }
  return copy
}
</script>

<template>
  <section v-loading="loading" class="review-panel">
    <header class="review-head">
      <div>
        <span class="eyebrow">复习模式</span>
        <h2>{{ currentIndexText }}</h2>
      </div>
      <button class="ghost-btn" type="button" @click="emit('close')">
        <el-icon><Close /></el-icon>
        退出复习
      </button>
    </header>

    <div v-if="!loading && !hasCards" class="review-empty">
      <strong>当前没有到期或新卡片</strong>
      <span>确认卡片后会自动进入复习队列。</span>
      <button class="ghost-btn" type="button" @click="openFullStats">查看统计</button>
      <ReviewStatsPanel v-if="showFullStats" :stats="fullStats" @close="showFullStats = false" />
    </div>

    <div v-else-if="!loading && isFinished" class="review-empty done">
      <strong>全部复习完成</strong>
      <div class="session-summary">
        <span>本轮 {{ sessionStats.total }} 张</span>
        <span>正确率 {{ accuracy }}%</span>
        <span>平均评分 {{ averageQuality }}</span>
      </div>
      <div v-if="lastAnswer" class="interval-preview">
        最近一次下次复习：{{ intervalText(lastAnswer.intervalDays) }}
      </div>
      <div class="done-actions">
        <button class="primary-btn" type="button" @click="loadReviewQueue">
          <el-icon><RefreshLeft /></el-icon>
          再来一轮
        </button>
        <button class="ghost-btn" type="button" @click="openFullStats">查看统计</button>
      </div>
      <ReviewStatsPanel v-if="showFullStats" :stats="fullStats" @close="showFullStats = false" />
    </div>

    <template v-else-if="currentCard">
      <button class="flip-stage" type="button" @click="flipCard">
        <article class="review-card" :class="{ flipped }">
          <div class="review-face front">
            <span class="type-badge">{{ currentCard.cardType === 'topic' ? '主题' : '概念' }}</span>
            <h3>{{ currentCard.title }}</h3>
            <p>{{ flipped ? '' : '点击查看内容' }}</p>
            <div class="keyword-row">
              <el-tag v-for="kw in currentCard.keywords.slice(0, 5)" :key="kw" size="small" effect="plain">
                {{ kw }}
              </el-tag>
            </div>
          </div>
          <div class="review-face back">
            <span class="group-name">{{ currentCard.reviewInfo?.nextReviewAt ? currentCard.groupPath || '未分组' : currentCard.groupPath || '新卡片' }}</span>
            <h3>{{ currentCard.title }}</h3>
            <section class="markdown-body content" v-html="currentHtml" />
            <div v-if="currentCard.reviewInfo?.reviewCount" class="interval-preview">
              当前间隔：{{ currentCard.reviewInfo.intervalDays }} 天 · 已复习 {{ currentCard.reviewInfo.reviewCount }} 次
            </div>
          </div>
        </article>
      </button>

      <div class="answer-row" :class="{ visible: flipped }">
        <button class="answer-btn forgot" type="button" :disabled="!flipped" @click="answerCurrent(0)">
          <el-icon><Warning /></el-icon>
          忘了
        </button>
        <button class="answer-btn fuzzy" type="button" :disabled="!flipped" @click="answerCurrent(3)">
          <el-icon><RefreshLeft /></el-icon>
          模糊
        </button>
        <button class="answer-btn known" type="button" :disabled="!flipped" @click="answerCurrent(5)">
          <el-icon><Check /></el-icon>
          熟悉
        </button>
      </div>

      <footer class="review-progress">
        <span>进度 {{ progressPercent }}%</span>
        <div class="progress-track">
          <i :style="{ width: `${progressPercent}%` }" />
        </div>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.review-panel {
  min-height: 560px;
  padding: 18px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
}

.review-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 18px;
}

.eyebrow {
  display: block;
  color: var(--text-secondary);
  font-size: 12px;
}

h2,
h3 {
  margin: 0;
  color: var(--text-primary);
}

h2 {
  margin-top: 4px;
  font-size: 22px;
}

.ghost-btn,
.primary-btn,
.answer-btn {
  height: 34px;
  border-radius: var(--radius-sm);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  white-space: nowrap;
}

.ghost-btn {
  padding: 0 14px;
  border: 1px solid var(--border);
  color: var(--text-primary);
  background: transparent;
}

.primary-btn {
  padding: 0 16px;
  border: 0;
  color: var(--bg-base);
  background: var(--accent);
}

.review-empty {
  min-height: 360px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 10px;
  color: var(--text-secondary);
  text-align: center;
}

.review-empty strong {
  color: var(--text-primary);
  font-size: 18px;
}

.done strong {
  color: var(--status-ok);
}

.session-summary,
.done-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
}

.session-summary span,
.interval-preview {
  padding: 6px 10px;
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: var(--bg-hover);
  font-size: 13px;
}

.flip-stage {
  width: min(720px, 100%);
  height: 360px;
  margin: 10px auto 18px;
  padding: 0;
  border: 0;
  background: transparent;
  perspective: 1000px;
  cursor: pointer;
  display: block;
}

.review-card {
  position: relative;
  width: 100%;
  height: 100%;
  transform-style: preserve-3d;
  transition: transform 0.48s ease;
}

.review-card.flipped {
  transform: rotateY(180deg);
}

.review-face {
  position: absolute;
  inset: 0;
  backface-visibility: hidden;
  -webkit-backface-visibility: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
  box-shadow: var(--shadow-md);
  overflow: hidden;
}

.front {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 14px;
  padding: 32px;
}

.front h3 {
  max-width: 580px;
  font-size: 28px;
  line-height: 1.45;
  text-align: center;
}

.front p {
  margin: 0;
  color: var(--text-secondary);
}

.back {
  transform: rotateY(180deg);
  padding: 24px;
  overflow-y: auto;
  text-align: left;
}

.back h3 {
  margin: 8px 0 14px;
  font-size: 20px;
  line-height: 1.45;
}

.group-name {
  color: var(--text-secondary);
  font-size: 12px;
}

.content {
  color: var(--text);
  line-height: 1.8;
}

.type-badge {
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: var(--bg-hover);
  border: 1px solid var(--border);
  font-size: 12px;
}

.keyword-row {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 6px;
  min-height: 24px;
}

.answer-row {
  display: flex;
  justify-content: center;
  gap: 12px;
  opacity: 0;
  transform: translateY(6px);
  pointer-events: none;
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.answer-row.visible {
  opacity: 1;
  transform: translateY(0);
  pointer-events: auto;
}

.answer-btn {
  min-width: 96px;
  padding: 0 16px;
  border: 1px solid var(--border);
  color: var(--text-primary);
  background: transparent;
}

.answer-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.answer-btn.forgot {
  border-color: var(--status-error);
  color: var(--status-error);
}

.answer-btn.fuzzy {
  border-color: var(--status-warning);
  color: var(--status-warning);
}

.answer-btn.known {
  border-color: var(--status-ok);
  color: var(--status-ok);
}

.review-progress {
  width: min(720px, 100%);
  margin: 18px auto 0;
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--text-secondary);
  font-size: 13px;
}

.progress-track {
  flex: 1;
  height: 8px;
  border-radius: 999px;
  background: var(--bg-hover);
  overflow: hidden;
}

.progress-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--accent);
  transition: width 0.2s ease;
}

@media (max-width: 760px) {
  .review-panel {
    padding: 14px;
  }

  .review-head,
  .answer-row,
  .review-progress {
    align-items: stretch;
    flex-direction: column;
  }

  .flip-stage {
    height: 420px;
  }

  .front h3 {
    font-size: 22px;
  }
}
</style>
