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
</template>

<style scoped>
.bar {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 12px 24px 18px;
  background: var(--bg-main);
  border-top: 1px solid var(--border);
  box-shadow: 0 -4px 24px rgba(0, 0, 0, 0.04);
}

.ipt {
  flex: 1;
}

:deep(.el-textarea__inner) {
  border-radius: 8px;
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.6;
  background: var(--bg-main);
  color: var(--text);
  border-color: var(--border);
}

:deep(.el-textarea__inner:focus) {
  border-color: var(--primary);
}

:deep(.el-textarea__inner::placeholder) {
  color: var(--text-secondary);
}

[data-theme='dark'] .bar {
  box-shadow: 0 -6px 28px rgba(0, 0, 0, 0.45);
}
</style>
