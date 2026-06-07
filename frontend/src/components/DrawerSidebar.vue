<script setup lang="ts">
import { onMounted, computed, ref, nextTick } from 'vue'
import {
  Plus,
  ChatLineRound,
  Delete,
  Edit,
  Close,
  Loading,
  Collection,
  Tickets,
  SwitchButton
} from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useChatStore } from '@/store/chat'
import { useAuthStore } from '@/store/auth'
import * as authApi from '@/api/auth'
import { formatRelativeTime } from '@/utils/time'
import KnowledgeUpload from '@/components/KnowledgeUpload.vue'
import { useDrawer } from '@/composables/useDrawer'
import type { SessionInfo } from '@/types/chat'

const router = useRouter()
const drawer = useDrawer()
const { open, closeDrawer } = drawer
const auth = useAuthStore()
const store = useChatStore()
const { sessions, activeSid, streaming } = storeToRefs(store)

const editingSid = ref<string | null>(null)
const editTitle = ref('')
const editInput = ref<HTMLInputElement | null>(null)
const { nickname } = storeToRefs(auth)

/** 有 token 但昵称尚未加载时显示轻量占位，避免侧栏闪动。 */
const profilePending = ref(!!auth.getToken() && !(nickname.value?.trim()))

const displayNickname = computed(() => {
  const n = nickname.value?.trim()
  if (n) return n
  if (profilePending.value) return '…'
  return ''
})

onMounted(async () => {
  store.refreshSessions()
  const t = auth.getToken()
  if (!t) {
    profilePending.value = false
    return
  }
  try {
    const me = await authApi.fetchMe()
    auth.applyProfile(me)
  } catch {
    /* 静默失败：仍可使用本地缓存昵称 */
  } finally {
    profilePending.value = false
  }
})

function handleSelect(sid: string) {
  if (streaming.value) return
  store.selectSession(sid)
  closeDrawer()
}

function handleNew() {
  if (streaming.value) return
  store.newSession()
  closeDrawer()
}

async function handleDelete(sid: string, e: Event) {
  e.stopPropagation()
  if (streaming.value) return
  try {
    await ElMessageBox.confirm('确定删除此会话？删除后不可恢复。', '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    store.deleteSession(sid)
  } catch {
    /* 用户取消 */
  }
}

function startRename(s: SessionInfo) {
  if (streaming.value) return
  editingSid.value = s.sessionId
  editTitle.value = s.title || ''
  void nextTick(() => editInput.value?.focus())
}

async function confirmRename(sid: string) {
  const t = editTitle.value.trim()
  if (!t) {
    cancelRename()
    return
  }
  const prev = sessions.value.find((x) => x.sessionId === sid)?.title || ''
  if (t === prev) {
    editingSid.value = null
    return
  }
  await store.rename(sid, t)
  editingSid.value = null
}

function cancelRename() {
  editingSid.value = null
  editTitle.value = ''
}

/** 仅当前编辑项 blur 时取消，避免切换编辑目标时旧输入框抢状态。 */
function handleEditBlur(sid: string) {
  if (editingSid.value === sid) cancelRename()
}

function goKnowledge() {
  if (streaming.value) return
  closeDrawer()
  void router.push('/knowledge')
}

function goCards() {
  if (streaming.value) return
  closeDrawer()
  void router.push('/cards')
}

async function handleLogout() {
  if (streaming.value) return
  try {
    await authApi.logoutApi()
  } catch {
    /* 服务端会话失效时仍清理本地态 */
  }
  auth.clearSession()
  closeDrawer()
  await router.replace('/login')
}
</script>

<template>
  <Teleport to="body">
    <Transition name="drawer">
      <div v-if="open" class="drawer-overlay" @click="closeDrawer">
        <aside class="drawer-sidebar" @click.stop>
          <div class="sidebar-header">
            <div class="brand-line">
              <span class="brand-emoji">🐟</span>
              <span class="brand-title">Fish Agent</span>
            </div>
            <div class="header-user">
              <span class="user-nickname">{{ displayNickname }}</span>
              <button class="icon-action danger" :disabled="streaming" title="退出" @click="handleLogout">
                <el-icon><SwitchButton /></el-icon>
              </button>
            </div>
            <button class="new-chat-btn" :disabled="streaming" @click="handleNew">
              <el-icon><Plus /></el-icon>
              新会话
            </button>
            <button class="action-btn" :disabled="streaming" @click="goKnowledge">
              <el-icon><Collection /></el-icon>
              知识库
            </button>
            <button class="action-btn" :disabled="streaming" @click="goCards">
              <el-icon><Tickets /></el-icon>
              知识卡片
            </button>
          </div>

          <div class="session-list" :class="{ locked: streaming }">
            <div
              v-for="s in sessions"
              :key="s.sessionId"
              class="session-item"
              :class="{ active: s.sessionId === activeSid, disabled: streaming, editing: editingSid === s.sessionId }"
              @click="handleSelect(s.sessionId)"
            >
              <el-icon class="item-icon">
                <Loading v-if="streaming && s.sessionId === activeSid" class="spin" />
                <ChatLineRound v-else />
              </el-icon>
              <div class="item-meta">
                <input
                  v-if="editingSid === s.sessionId"
                  ref="editInput"
                  v-model="editTitle"
                  class="name-edit"
                  @click.stop
                  @dblclick.stop
                  @blur="handleEditBlur(s.sessionId)"
                  @keydown.enter.prevent="confirmRename(s.sessionId)"
                  @keyup.escape="cancelRename"
                />
                <div v-else class="name">{{ s.title || '新会话' }}</div>
                <div class="sub">{{ s.messageCount }} 条 · {{ formatRelativeTime(s.updatedAt) }}</div>
              </div>
              <button
                v-if="!streaming"
                class="icon-action"
                :title="editingSid === s.sessionId ? '取消' : '重命名'"
                @click.stop="editingSid === s.sessionId ? cancelRename() : startRename(s)"
                @mousedown.prevent
              >
                <el-icon>
                  <Close v-if="editingSid === s.sessionId" />
                  <Edit v-else />
                </el-icon>
              </button>
              <button
                class="icon-action danger"
                :disabled="streaming"
                title="删除"
                @click="handleDelete(s.sessionId, $event)"
              >
                <el-icon><Delete /></el-icon>
              </button>
            </div>

            <div v-if="sessions.length === 0" class="empty">暂无会话</div>
          </div>

          <KnowledgeUpload :disabled="streaming" />

          <div class="footer">
            <span v-if="streaming" class="streaming-tip">
              <el-icon class="spin"><Loading /></el-icon>
              正在生成…
            </span>
            <span v-else class="hint">Enter 发送 · Shift+Enter 换行</span>
          </div>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.drawer-overlay {
  position: fixed;
  inset: 0;
  z-index: 30;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

.drawer-sidebar {
  width: 300px;
  max-width: 86vw;
  height: 100%;
  background: var(--bg-glass-heavy);
  border-right: 1px solid var(--border);
  box-shadow: var(--shadow-lg);
  backdrop-filter: var(--glass-blur-heavy);
  -webkit-backdrop-filter: var(--glass-blur-heavy);
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  padding: 16px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  border-bottom: 1px solid var(--border);
}

.brand-line,
.header-user {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.brand-emoji {
  font-size: 18px;
  line-height: 1;
  animation: float 3s ease-in-out infinite;
}

.brand-title {
  font-weight: 700;
  font-size: 16px;
  background: var(--gradient-brand);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.header-user {
  justify-content: flex-end;
}

.user-nickname {
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-secondary);
  font-size: 13px;
}

.new-chat-btn,
.action-btn {
  height: 34px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  background: rgba(99, 102, 241, 0.1);
  color: var(--text-primary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  transition: border-color var(--transition-fast), background var(--transition-fast), transform var(--transition-fast);
}

.new-chat-btn:hover:not(:disabled),
.action-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  border-color: var(--primary);
  transform: translateY(-1px);
}

.new-chat-btn:disabled,
.action-btn:disabled,
.icon-action:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 10px;
}

.session-list.locked .session-item:not(.active) {
  cursor: not-allowed;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background var(--transition-fast), transform var(--transition-fast), box-shadow var(--transition-fast);
}

.session-item:hover:not(.disabled) {
  background: var(--bg-hover);
  transform: translateX(2px);
}

.session-item.active {
  background: var(--bg-active);
  box-shadow: inset 3px 0 0 var(--primary);
}

.session-item.disabled {
  opacity: 0.75;
}

.item-icon {
  color: var(--primary-light);
  font-size: 18px;
}

.item-meta {
  flex: 1;
  min-width: 0;
}

.name {
  font-size: 13.5px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.name-edit {
  display: block;
  width: 100%;
  font-size: 13.5px;
  font-family: inherit;
  color: var(--text-primary);
  background: transparent;
  border: none;
  border-bottom: 1px solid transparent;
  padding: 0 0 1px;
  outline: none;
}

.name-edit:focus {
  border-bottom-color: var(--primary);
}

.sub {
  font-size: 11.5px;
  color: var(--text-secondary);
  margin-top: 2px;
}

.icon-action {
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: transparent;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--transition-fast), color var(--transition-fast), background var(--transition-fast);
}

.session-item:hover .icon-action,
.session-item.editing .icon-action,
.header-user .icon-action {
  opacity: 1;
}

.icon-action:hover:not(:disabled) {
  color: var(--primary-light);
  background: var(--bg-hover);
}

.icon-action.danger:hover:not(:disabled) {
  color: #f87171;
}

.empty {
  text-align: center;
  color: var(--text-secondary);
  font-size: 13px;
  padding: 32px 0;
}

.empty::before {
  content: '💬';
  display: block;
  font-size: 32px;
  margin-bottom: 8px;
}

.footer {
  padding: 10px 16px;
  border-top: 1px solid var(--border);
  font-size: 12px;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 36px;
}

.streaming-tip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--primary-light);
}

.spin {
  animation: spin 1s linear infinite;
}

.drawer-enter-active,
.drawer-leave-active {
  transition: opacity var(--transition-normal);
}

.drawer-enter-active .drawer-sidebar,
.drawer-leave-active .drawer-sidebar {
  transition: transform var(--transition-normal);
}

.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
}

.drawer-enter-from .drawer-sidebar,
.drawer-leave-to .drawer-sidebar {
  transform: translateX(-100%);
}
</style>
