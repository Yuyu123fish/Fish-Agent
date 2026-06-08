<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { Promotion, CircleClose } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { useChatStore } from '@/store/chat'

const store = useChatStore()
const { streaming, activeSid } = storeToRefs(store)

const text = ref('')
const iptRef = ref<any>(null)

function focusInput(): void {
  // 等 el-input 重新渲染完，再 focus
  setTimeout(() => iptRef.value?.focus?.(), 30)
}

function submit() {
  const v = text.value.trim()
  if (!v) return
  if (streaming.value) return
  text.value = ''
  store.send(v).then(() => focusInput())
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submit()
  }
}

onMounted(() => focusInput())

// 流结束 / 切换会话 / 新建会话时自动聚焦
watch(streaming, (v) => {
  if (!v) focusInput()
})
watch(activeSid, () => focusInput())
</script>

<template>
  <div class="bar">
    <div class="ipt-wrap">
      <el-input
        ref="iptRef"
        v-model="text"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 6 }"
        placeholder="输入问题，Enter 发送 · Shift+Enter 换行"
        resize="none"
        class="ipt"
        @keydown="onKeydown"
      />
      <el-button
        v-if="!streaming"
        type="primary"
        :icon="Promotion"
        circle
        :disabled="!text.trim()"
        @click="submit"
      />
      <el-button
        v-else
        type="danger"
        :icon="CircleClose"
        circle
        @click="store.cancel"
      />
    </div>
  </div>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: flex-end;
  gap: 0;
  padding: 12px 24px 20px;
  max-width: 800px;
  width: 100%;
  margin: 0 auto;
  background: transparent;
  border-top: none;
  box-shadow: none;
}

.ipt-wrap {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  width: 100%;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 6px 6px 6px 16px;
  box-shadow: var(--shadow-md);
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.ipt-wrap:focus-within {
  border-color: var(--border-bright);
  box-shadow: var(--shadow-md);
}

.ipt {
  flex: 1;
}

:deep(.el-textarea__inner) {
  border: none !important;
  padding: 8px 0;
  font-size: 14px;
  line-height: 1.6;
  background: transparent;
  color: var(--text);
  box-shadow: none !important;
}

:deep(.el-textarea__inner::placeholder) {
  color: var(--text-secondary);
}

:deep(.el-button--primary.is-circle) {
  background: var(--accent) !important;
  border: none !important;
  color: var(--bg-base) !important;
  width: 36px;
  height: 36px;
  transition: opacity var(--transition-fast);
}

:deep(.el-button--primary.is-circle:hover) {
  opacity: 0.85;
}

:deep(.el-button--danger.is-circle) {
  background: var(--bg-surface) !important;
  border-color: var(--border) !important;
  color: var(--text-primary) !important;
}
</style>
