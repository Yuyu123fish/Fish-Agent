<script setup lang="ts">
import { onErrorCaptured, ref } from 'vue'
import { RouterView } from 'vue-router'

const hasError = ref(false)

onErrorCaptured((err) => {
  console.error('[FishAgent] 组件异常:', err)
  hasError.value = true
  return false
})

function retry() {
  hasError.value = false
}
</script>

<template>
  <div v-if="hasError" class="error-boundary">
    <div class="error-icon">⚠️</div>
    <h2>出了点问题</h2>
    <p>页面遇到了意外错误，请尝试刷新。</p>
    <button class="retry-btn" @click="retry">重试</button>
  </div>
  <RouterView v-else />
</template>

<style scoped>
.error-boundary {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: var(--bg-page);
  color: var(--text-primary);
}

.error-icon {
  font-size: 48px;
  margin-bottom: 16px;
}

.error-boundary h2 {
  font-size: 20px;
  font-weight: 600;
  margin: 0 0 8px;
}

.error-boundary p {
  color: var(--text-secondary);
  margin: 0 0 24px;
}

.retry-btn {
  padding: 8px 24px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  background: var(--bg-main);
  color: var(--text-primary);
  cursor: pointer;
  font-size: 14px;
  transition: border-color var(--transition-fast), color var(--transition-fast);
}

.retry-btn:hover {
  border-color: var(--primary);
  color: var(--primary);
}
</style>
