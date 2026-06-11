<script setup lang="ts">
import { onErrorCaptured, onMounted, onUnmounted, ref } from 'vue'
import { RouterView } from 'vue-router'
import MeshBackground from '@/components/MeshBackground.vue'
import { useDrawer } from '@/composables/useDrawer'

const hasError = ref(false)
const errorMessage = ref('')
const errorStack = ref('')
const drawer = useDrawer()

onErrorCaptured((err) => {
  console.error('[FishAgent] 组件异常:', err)
  errorMessage.value = err instanceof Error ? err.message : String(err)
  errorStack.value = err instanceof Error ? (err.stack ?? '') : ''
  hasError.value = true
  return false
})

function retry() {
  hasError.value = false
}

function onGlobalKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  if (drawer.open.value) {
    drawer.closeDrawer()
  }
}

onMounted(() => {
  document.addEventListener('keydown', onGlobalKeydown)
})

onUnmounted(() => {
  document.removeEventListener('keydown', onGlobalKeydown)
})
</script>

<template>
  <MeshBackground />
  <div v-if="hasError" class="error-boundary">
    <div class="error-icon">⚠️</div>
    <h2>出了点问题</h2>
    <p class="error-message">{{ errorMessage }}</p>
    <pre v-if="errorStack" class="error-stack">{{ errorStack }}</pre>
    <button class="retry-btn" @click="retry">重试</button>
  </div>
  <RouterView v-else v-slot="{ Component }">
    <Transition name="page">
      <KeepAlive include="ChatView">
        <component :is="Component" :key="$route.path" />
      </KeepAlive>
    </Transition>
  </RouterView>
</template>

<style scoped>
.error-boundary {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100vh;
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
  background: var(--bg-elevated);
  color: var(--text-primary);
  cursor: pointer;
  font-size: 14px;
  transition: border-color var(--transition-fast), color var(--transition-fast);
}

.retry-btn:hover {
  border-color: var(--accent);
  color: var(--text-primary);
}

.error-message {
  color: var(--status-error);
  font-size: 14px;
  margin: 0 0 12px;
  max-width: 600px;
  text-align: center;
  word-break: break-word;
}

.error-stack {
  color: var(--text-secondary);
  font-size: 12px;
  max-width: 700px;
  max-height: 200px;
  overflow: auto;
  padding: 12px;
  border-radius: var(--radius-sm);
  background: var(--bg-sunken);
  border: 1px solid var(--border);
  margin: 0 0 16px;
  text-align: left;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>

<style>
/* 路由过渡需要全局作用域，避免 scoped 属性影响 Transition 类名匹配。
   注意：不能使用 mode="out-in"，它与 <KeepAlive> + 动态组件 :key 组合时会跳过进入阶段，
   导致切换后新页面不渲染（白屏）。改用默认同时过渡，并让离场页绝对定位以避免布局跳动。 */
.page-enter-active,
.page-leave-active {
  transition: opacity 0.15s ease;
}

.page-leave-active {
  position: absolute;
  inset: 0;
  z-index: 0;
}

.page-enter-from,
.page-leave-to {
  opacity: 0;
}
</style>
