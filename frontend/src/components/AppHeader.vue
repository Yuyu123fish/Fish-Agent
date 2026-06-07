<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { Menu, Collection, SwitchButton, ArrowLeft, Moon, Sunny, Tickets } from '@element-plus/icons-vue'
import { useAuthStore } from '@/store/auth'
import * as authApi from '@/api/auth'
import { useDrawer } from '@/composables/useDrawer'
import { useTheme } from '@/composables/useTheme'

defineProps<{
  /** 是否显示返回按钮（知识库页用） */
  showBack?: boolean
}>()

const emit = defineEmits<{
  back: []
}>()

const router = useRouter()
const { openDrawer } = useDrawer()
const { dark, toggle } = useTheme()
const auth = useAuthStore()
const { nickname } = storeToRefs(auth)

const displayNickname = computed(() => nickname.value?.trim() || '')

async function handleLogout() {
  try {
    await authApi.logoutApi()
  } catch {
    /* 服务端会话失效时仍清理本地态 */
  }
  auth.clearSession()
  await router.replace('/login')
}

function goKnowledge() {
  void router.push('/knowledge')
}

function goCards() {
  void router.push('/cards')
}
</script>

<template>
  <header class="app-header">
    <div class="left">
      <button class="icon-btn" title="菜单" @click="openDrawer">
        <el-icon :size="18"><Menu /></el-icon>
      </button>
      <button v-if="showBack" class="icon-btn back-btn" title="返回" @click="emit('back')">
        <el-icon :size="16"><ArrowLeft /></el-icon>
      </button>
      <div class="brand">
        <span class="brand-emoji">🐟</span>
        <span class="brand-title">Fish Agent</span>
      </div>
    </div>
    <div class="right">
      <button class="icon-btn" title="知识库" @click="goKnowledge">
        <el-icon :size="16"><Collection /></el-icon>
      </button>
      <button class="icon-btn" title="知识卡片" @click="goCards">
        <el-icon :size="16"><Tickets /></el-icon>
      </button>
      <button class="icon-btn theme-toggle" :title="dark ? '切换亮色' : '切换暗色'" @click="toggle">
        <el-icon :size="16">
          <Sunny v-if="dark" />
          <Moon v-else />
        </el-icon>
      </button>
      <span v-if="displayNickname" class="user-name">{{ displayNickname }}</span>
      <button class="icon-btn logout" title="退出" @click="handleLogout">
        <el-icon :size="16"><SwitchButton /></el-icon>
      </button>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 48px;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: var(--bg-header);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-bottom: 1px solid var(--border);
}

.left,
.right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.icon-btn {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: color var(--transition-fast), background var(--transition-fast), border-color var(--transition-fast);
}

.icon-btn:hover {
  color: var(--primary-light);
  background: var(--bg-hover);
  border-color: var(--border);
}

.logout:hover {
  color: #f87171;
}

.theme-toggle {
  transition: transform var(--transition-normal), color var(--transition-fast);
}

.theme-toggle:hover {
  transform: rotate(30deg);
}

.brand {
  display: flex;
  align-items: center;
  gap: 6px;
}

.brand-emoji {
  font-size: 18px;
  line-height: 1;
}

.brand-title {
  font-weight: 600;
  font-size: 16px;
  background: var(--gradient-brand);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.user-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
