<script setup lang="ts">
import { computed, onDeactivated, onUnmounted, ref, watch } from 'vue'
import { useChatStore } from '@/store/chat'
import { ElMessage } from 'element-plus'
import { Tickets } from '@element-plus/icons-vue'
import AppHeader from '@/components/AppHeader.vue'
import DrawerSidebar from '@/components/DrawerSidebar.vue'
import MessageList from '@/components/MessageList.vue'
import ChatInput from '@/components/ChatInput.vue'
import CardExtractPreview from '@/components/CardExtractPreview.vue'
import { extractCards, type ExtractResult } from '@/api/card'

defineOptions({ name: 'ChatView' })

const store = useChatStore()
const extracting = ref(false)
const previewVisible = ref(false)
const extractResult = ref<ExtractResult | null>(null)
const hintedSessions = ref<Record<string, boolean>>({})
const hintDismissed = ref(false)

const knowledgePattern = /概念|原理|流程|区别|机制|架构|分析|理解|总结|比较|框架|算法|设计模式/
const activeSid = computed(() => store.activeSid)
const canExtract = computed(() => !!activeSid.value && activeSid.value !== '__pending__' && store.messages.length > 0)
const showPassiveHint = computed(() => {
  const sid = activeSid.value
  if (!sid || hintedSessions.value[sid] || hintDismissed.value || store.streaming || extracting.value) return false
  if (store.messages.length < 16) return false
  return knowledgePattern.test(store.messages.map((m) => m.content).join('\n'))
})

watch(activeSid, () => {
  hintDismissed.value = false
})

async function runExtract(fromHint = false) {
  if (!canExtract.value || extracting.value) return
  extracting.value = true
  if (fromHint && activeSid.value) {
    hintedSessions.value = { ...hintedSessions.value, [activeSid.value]: true }
  }
  try {
    extractResult.value = await extractCards(activeSid.value)
    previewVisible.value = true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '提取失败，请稍后重试')
  } finally {
    extracting.value = false
  }
}

function closePreview() {
  previewVisible.value = false
}

function handlePreviewConfirmed() {
  previewVisible.value = false
  ElMessage.success('知识卡片已确认，可在知识卡片页查看')
}

onDeactivated(() => {
  store.cleanup()
})

onUnmounted(() => {
  store.cleanup()
})
</script>

<template>
  <div class="layout">
    <AppHeader />
    <DrawerSidebar />
    <main class="main">
      <MessageList />
      <section class="extract-zone">
        <button
          class="extract-btn"
          type="button"
          :disabled="!canExtract || store.streaming || extracting"
          @click="runExtract(false)"
        >
          <el-icon><Tickets /></el-icon>
          {{ extracting ? '正在提取…' : '提取知识卡片' }}
        </button>
        <button
          v-if="showPassiveHint"
          class="hint-bar"
          type="button"
          @click="runExtract(true)"
        >
          这段对话包含多个知识点，点击提取为知识卡片
        </button>
      </section>
      <ChatInput />
    </main>
    <CardExtractPreview
      :visible="previewVisible"
      :result="extractResult"
      @close="closePreview"
      @confirmed="handlePreviewConfirmed"
    />
  </div>
</template>

<style scoped>
.layout {
  position: relative;
  z-index: 1;
  display: flex;
  height: 100vh;
  width: 100vw;
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding-top: 48px;
  background: transparent;
}

.extract-zone {
  max-width: 800px;
  width: 100%;
  margin: 0 auto;
  padding: 0 24px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.extract-btn,
.hint-bar {
  height: 34px;
  border-radius: var(--radius-sm);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  transition: border-color var(--transition-fast), color var(--transition-fast), background var(--transition-fast);
}

.extract-btn {
  padding: 0 16px;
  border: 1px solid var(--border-bright);
  color: var(--text-primary);
  background: var(--bg-surface);
  font-weight: 500;
}

.extract-btn:hover:not(:disabled) {
  border-color: var(--accent);
  background: var(--bg-hover);
}

.extract-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.hint-bar {
  flex: 1;
  min-width: 0;
  padding: 0 12px;
  border: 1px solid var(--status-warning);
  color: var(--status-warning);
  background: rgba(143, 135, 107, 0.08);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 720px) {
  .extract-zone {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
