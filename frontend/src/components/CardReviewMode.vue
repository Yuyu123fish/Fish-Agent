<script setup lang="ts">
/**
 * 知识卡片复习模式。
 *
 * 当前阶段只做前端临时队列；后续如果要持久化复习记录，可以把 answerCurrent 的队列策略替换为后端调度结果。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { marked } from 'marked'
import { ElMessage } from 'element-plus'
import { Check, Close, RefreshLeft, Warning } from '@element-plus/icons-vue'
import { getCard, listCards, type CardDetail } from '@/api/card'

const props = defineProps<{
  groupId?: number | null
  groupName?: string
}>()

const emit = defineEmits<{
  close: []
}>()

type ReviewAction = 'forgot' | 'fuzzy' | 'known'

const loading = ref(false)
const queue = ref<CardDetail[]>([])
const total = ref(0)
const completedCount = ref(0)
const flipped = ref(false)

const currentCard = computed(() => queue.value[0] ?? null)
const hasCards = computed(() => total.value > 0)
const isFinished = computed(() => hasCards.value && queue.value.length === 0)
const progressPercent = computed(() => {
  if (total.value === 0) return 0
  return Math.round((completedCount.value / total.value) * 100)
})
const currentIndexText = computed(() => {
  if (!currentCard.value) return `${completedCount.value}/${total.value}`
  return `${Math.min(completedCount.value + 1, total.value)}/${total.value}`
})
const currentHtml = computed(() => (currentCard.value?.content ? (marked.parse(currentCard.value.content) as string) : ''))

onMounted(() => void loadReviewQueue())

watch(
  () => [props.groupId, props.groupName] as const,
  () => void loadReviewQueue()
)

async function loadReviewQueue() {
  loading.value = true
  flipped.value = false
  completedCount.value = 0
  try {
    const page = await listCards({
      page: 1,
      size: 9999,
      status: 'confirmed',
      groupId: props.groupId && props.groupId > 0 ? props.groupId : undefined,
      groupName: props.groupId ? undefined : props.groupName
    })
    // 列表接口只返回预览，复习背面需要完整正文，因此按卡片详情补齐内容。
    const details = await Promise.all(page.records.map((item) => getCard(item.id)))
    queue.value = shuffle(details)
    total.value = details.length
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

function answerCurrent(action: ReviewAction) {
  if (!currentCard.value) return
  const [head, ...rest] = queue.value
  if (action === 'known') {
    completedCount.value += 1
    queue.value = rest
  } else {
    // 模糊和忘了都延后复现；保留 action 参数是为了后续扩展不同间隔策略。
    queue.value = [...rest, head]
  }
  flipped.value = false
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
      <strong>当前分组没有可复习的卡片</strong>
      <span>确认卡片后即可进入复习队列。</span>
    </div>

    <div v-else-if="!loading && isFinished" class="review-empty done">
      <strong>全部复习完成</strong>
      <span>这轮卡片都已标记为熟悉。</span>
      <button class="primary-btn" type="button" @click="loadReviewQueue">
        <el-icon><RefreshLeft /></el-icon>
        再来一轮
      </button>
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
            <span class="group-name">{{ currentCard.groupPath || currentCard.groupName || '未分组' }}</span>
            <h3>{{ currentCard.title }}</h3>
            <section class="markdown-body content" v-html="currentHtml" />
          </div>
        </article>
      </button>

      <div class="answer-row" :class="{ visible: flipped }">
        <button class="answer-btn forgot" type="button" :disabled="!flipped" @click="answerCurrent('forgot')">
          <el-icon><Warning /></el-icon>
          忘了
        </button>
        <button class="answer-btn fuzzy" type="button" :disabled="!flipped" @click="answerCurrent('fuzzy')">
          <el-icon><RefreshLeft /></el-icon>
          模糊
        </button>
        <button class="answer-btn known" type="button" :disabled="!flipped" @click="answerCurrent('known')">
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
  border-radius: var(--radius-lg);
  background: rgba(10, 10, 20, 0.28);
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
  color: #fff;
  background: var(--gradient-brand);
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
  color: #4ade80;
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
  border-radius: var(--radius-lg);
  background:
    linear-gradient(135deg, rgba(99, 102, 241, 0.12), rgba(52, 211, 153, 0.08)),
    var(--bg-glass);
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
  color: var(--primary-light);
  background: rgba(99, 102, 241, 0.12);
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
  border-color: rgba(248, 113, 113, 0.36);
  color: #f87171;
}

.answer-btn.fuzzy {
  border-color: rgba(251, 191, 36, 0.36);
  color: #fbbf24;
}

.answer-btn.known {
  border-color: rgba(74, 222, 128, 0.36);
  color: #4ade80;
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
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
}

.progress-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #6366f1, #34d399);
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
