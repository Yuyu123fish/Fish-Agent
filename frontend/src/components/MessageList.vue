<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { ArrowDown } from '@element-plus/icons-vue'
import { useChatStore } from '@/store/chat'
import MessageBubble from './MessageBubble.vue'

const store = useChatStore()
const { messages, errorMsg, streaming } = storeToRefs(store)
const RENDER_BATCH = 30
const renderCount = ref(RENDER_BATCH)

const visibleMessages = computed(() => {
  if (messages.value.length <= renderCount.value) return messages.value
  return messages.value.slice(-renderCount.value)
})

const hasMore = computed(() => renderCount.value < messages.value.length)

const suggestions = [
  '帮我写一段 Python 爬虫',
  '搜索今天的 AI 行业动态',
  '解释一下 RAG 是什么',
  '帮我做一份周报大纲'
] as const

function sendSuggestion(text: string): void {
  if (streaming.value) return
  void store.send(text)
}

const scroller = ref<HTMLElement | null>(null)
/**
 * 用户是否处于"贴近底部"状态。
 * - 距离底部 < 80px 视为粘底；
 * - 粘底时新消息会自动滚到底，否则不打扰用户阅读历史。
 */
const stickToBottom = ref(true)

function updateStick(): void {
  const el = scroller.value
  if (!el) return
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight
  stickToBottom.value = distance < 80
}

async function scrollToBottom(force = false): Promise<void> {
  await nextTick()
  const el = scroller.value
  if (!el) return
  if (force || stickToBottom.value) {
    if (force) {
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
    } else {
      el.scrollTop = el.scrollHeight
    }
    stickToBottom.value = true
  }
}

function jumpToBottom(): void {
  scrollToBottom(true)
}

function loadMore(): void {
  renderCount.value = Math.min(renderCount.value + RENDER_BATCH, messages.value.length)
}

watch(
  messages,
  (curr, prev) => {
    const newMsgCount = curr.length - (prev?.length ?? 0)
    if (newMsgCount > 0) {
      renderCount.value = Math.min(renderCount.value + newMsgCount, curr.length)
    }
    scrollToBottom(false)
  },
  { deep: true, immediate: true }
)

// 切换会话时强制滚到底
watch(
  () => store.activeSid,
  () => {
    renderCount.value = RENDER_BATCH
    stickToBottom.value = true
    scrollToBottom(true)
  }
)

onMounted(() => {
  updateStick()
})
</script>

<template>
  <div class="wrap" ref="scroller" @scroll="updateStick">
    <div class="messages-center">
      <button v-if="hasMore" class="load-more" type="button" @click="loadMore">
        加载更早消息（剩余 {{ messages.length - renderCount }} 条）
      </button>

      <div v-if="messages.length === 0" class="welcome">
        <div class="welcome-glow"></div>
        <div class="logo">🐟</div>
        <div class="hello">你好，我是 Fish Agent</div>
        <div class="tip">问我任何事，我会调用合适的工具帮你解决。</div>
        <div class="suggestions">
          <button
            v-for="(s, idx) in suggestions"
            :key="idx"
            type="button"
            class="suggestion-card"
            :disabled="streaming"
            @click="sendSuggestion(s)"
          >
            {{ s }}
          </button>
        </div>
      </div>

      <MessageBubble
        v-for="(m, i) in visibleMessages"
        :key="messages.length - visibleMessages.length + i"
        :msg="m"
        :is-last="i === visibleMessages.length - 1"
        :streaming="streaming"
      />

      <el-alert
        v-if="errorMsg"
        :title="errorMsg"
        type="error"
        show-icon
        :closable="false"
        class="err"
      />
    </div>

    <transition name="fade">
      <button
        v-if="!stickToBottom"
        class="to-bottom"
        type="button"
        @click="jumpToBottom"
        title="回到底部"
      >
        <el-icon><ArrowDown /></el-icon>
      </button>
    </transition>
  </div>
</template>

<style scoped>
.wrap {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px;
  display: flex;
  flex-direction: column;
  position: relative;
}

.messages-center {
  max-width: 800px;
  width: 100%;
  margin: 0 auto;
}

.load-more {
  display: block;
  width: 100%;
  padding: 10px;
  margin-bottom: 8px;
  border: 1px dashed var(--border);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: color var(--transition-fast), border-color var(--transition-fast);
}

.load-more:hover {
  color: var(--primary);
  border-color: var(--primary);
}

.welcome {
  position: relative;
  margin: auto 0;
  text-align: center;
  color: var(--text-secondary);
}

.welcome-glow {
  position: absolute;
  width: 200px;
  height: 200px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(79, 70, 229, 0.12), transparent 70%);
  top: -40px;
  left: 50%;
  transform: translateX(-50%);
  pointer-events: none;
}

[data-theme='dark'] .welcome-glow {
  background: radial-gradient(circle, rgba(99, 102, 241, 0.15), transparent 70%);
}

.welcome .logo {
  font-size: 56px;
  animation: float 3s ease-in-out infinite;
  position: relative;
  z-index: 1;
}

.welcome .hello {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  margin-top: 16px;
}

.welcome .tip {
  margin-top: 6px;
  font-size: 14px;
}

.suggestions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  max-width: 560px;
  margin: 32px auto 0;
  padding: 0 8px;
}

.suggestion-card {
  text-align: left;
  padding: 14px 16px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--text);
  background: var(--bg-main);
  border: 1px solid var(--border);
  border-left: 3px solid var(--primary);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition:
    border-color var(--transition-fast),
    box-shadow var(--transition-fast),
    transform var(--transition-fast),
    background var(--transition-fast);
  box-shadow: var(--shadow-sm);
}

.suggestion-card:hover:not(:disabled) {
  border-color: var(--primary);
  box-shadow: 0 6px 20px rgba(79, 70, 229, 0.15);
  transform: translateY(-2px);
  background: var(--bg-hover);
}

.suggestion-card:active:not(:disabled) {
  transform: translateY(0);
}

.suggestion-card:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

[data-theme='dark'] .suggestion-card:hover:not(:disabled) {
  box-shadow: 0 4px 18px rgba(99, 102, 241, 0.18);
}

.err {
  margin-top: 12px;
}

.to-bottom {
  position: sticky;
  bottom: 12px;
  align-self: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid var(--border);
  background: var(--bg-main);
  color: var(--text-primary);
  cursor: pointer;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.15s ease;
}

.to-bottom:hover {
  transform: translateY(-2px);
  border-color: var(--primary);
  color: var(--primary);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.18s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
