<script setup lang="ts">
import { onMounted, computed, ref, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import {
  Plus,
  ChatLineRound,
  Delete,
  Edit,
  Close,
  Loading,
  SwitchButton,
  Collection,
  Moon,
  Sunny
} from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { ElMessageBox } from 'element-plus'
import { useChatStore } from '@/store/chat'
import { useAuthStore } from '@/store/auth'
import * as authApi from '@/api/auth'
import { formatRelativeTime } from '@/utils/time'
import KnowledgeUpload from '@/components/KnowledgeUpload.vue'
import { useTheme } from '@/composables/useTheme'
import type { SessionInfo } from '@/types/chat'

const router = useRouter()
const { dark, toggle } = useTheme()
const auth = useAuthStore()
const store = useChatStore()
const { sessions, activeSid, streaming } = storeToRefs(store)

const editingSid = ref<string | null>(null)
const editTitle = ref('')
const editInput = ref<HTMLInputElement | null>(null)
const { nickname } = storeToRefs(auth)

/** 有 token 但尚未从 /me 拿到昵称前为 true（本地已有昵称时不显示省略号） */
const profilePending = ref(
  !!auth.getToken() && !(nickname.value?.trim()),
)

const displayNickname = computed(() => {
  const n = nickname.value?.trim()
  if (n && n.length > 0) {
    return n
  }
  if (profilePending.value) {
    return '…'
  }
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
    /* 静默失败：仍可用本地缓存昵称 */
  } finally {
    profilePending.value = false
  }
})

function handleSelect(sid: string) {
  if (streaming.value) return
  store.selectSession(sid)
}

function handleNew() {
  if (streaming.value) return
  store.newSession()
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
    // 用户取消
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

/** 仅当该会话仍处于编辑态时取消，防止切换编辑目标时旧 blur 冲突 */
function handleEditBlur(sid: string) {
  if (editingSid.value === sid) {
    cancelRename()
  }
}

/**
 * 退出登录并跳转登录页。
 */
async function handleLogout() {
  if (streaming.value) return
  try {
    await authApi.logoutApi()
  } catch {
    /* 服务端会话失效时仍清理本地态 */
  }
  auth.clearSession()
  await router.replace('/login')
}

/** 跳转知识库管理页（上传、列表、删除） */
function goKnowledge() {
  if (streaming.value) return
  void router.push('/knowledge')
}
</script>

<template>
  <aside class="sidebar">
    <div class="header">
      <div class="brand-line">
        <el-button
          class="theme-toggle"
          :icon="dark ? Sunny : Moon"
          circle
          size="small"
          text
          type="primary"
          title="切换明 / 暗主题"
          @click="toggle"
        />
        <span class="brand-emoji">🐟</span>
        <span class="title">Fish Agent</span>
      </div>
      <div class="header-user-top">
        <span class="user-nickname" :title="nickname?.trim() || undefined">{{ displayNickname }}</span>
      </div>
      <el-button
        type="primary"
        plain
        :icon="Plus"
        round
        size="small"
        class="new-chat-btn"
        :disabled="streaming"
        @click="handleNew"
      >
        新会话
      </el-button>
      <div class="header-user-bottom">
        <el-button
          class="kb-nav-btn"
          :icon="Collection"
          round
          size="small"
          text
          :disabled="streaming"
          title="知识库管理"
          @click="goKnowledge"
        >
          知识库
        </el-button>
        <el-button
          class="logout-btn"
          :icon="SwitchButton"
          round
          size="small"
          text
          :disabled="streaming"
          @click="handleLogout"
        >
          退出
        </el-button>
      </div>
    </div>

    <div class="list" :class="{ locked: streaming }">
      <div
        v-for="s in sessions"
        :key="s.sessionId"
        class="item"
        :class="{ active: s.sessionId === activeSid, disabled: streaming, editing: editingSid === s.sessionId }"
        @click="handleSelect(s.sessionId)"
      >
        <el-icon class="icon">
          <Loading v-if="streaming && s.sessionId === activeSid" class="spin" />
          <ChatLineRound v-else />
        </el-icon>
        <div class="meta">
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
        <el-button
          v-if="!streaming"
          link
          :icon="editingSid === s.sessionId ? Close : Edit"
          class="edit-btn"
          :title="editingSid === s.sessionId ? '取消' : '重命名'"
          @click.stop="editingSid === s.sessionId ? cancelRename() : startRename(s)"
          @mousedown.prevent
        />
        <el-button
          link
          :icon="Delete"
          class="del"
          :disabled="streaming"
          @click="handleDelete(s.sessionId, $event)"
        />
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
</template>

<style scoped>
.sidebar {
  width: 260px;
  height: 100%;
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
}

.header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-rows: auto auto;
  align-items: center;
  column-gap: 14px;
  row-gap: 10px;
  padding: 14px 16px 16px;
  border-bottom: 1px solid var(--border);
  background-image: linear-gradient(to right, transparent, var(--primary), transparent);
  background-size: 60% 1px;
  background-position: bottom;
  background-repeat: no-repeat;
}

.brand-line {
  grid-column: 1;
  grid-row: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.theme-toggle {
  flex-shrink: 0;
  transition: transform var(--transition-normal);
}

.theme-toggle:hover {
  transform: rotate(30deg);
}

.brand-emoji {
  font-size: 16px;
  line-height: 1;
}

.title {
  font-weight: 600;
  font-size: 16px;
  line-height: 1.25;
  flex-shrink: 0;
  background: var(--gradient-brand);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.header-user-top {
  grid-column: 2;
  grid-row: 1;
  justify-self: end;
  min-width: 0;
  max-width: 120px;
}

.header-user-bottom {
  grid-column: 2;
  grid-row: 2;
  justify-self: end;
  display: flex;
  align-items: center;
  gap: 4px;
}

.kb-nav-btn {
  color: var(--primary);
  padding: 4px 8px;
}

.new-chat-btn {
  grid-column: 1;
  grid-row: 2;
  justify-self: start;
  width: auto;
  padding: 5px 12px;
  font-weight: 500;
  --el-button-bg-color: transparent;
}

.new-chat-btn.is-disabled,
.new-chat-btn.is-disabled:hover {
  opacity: 0.5;
  --el-button-disabled-bg-color: transparent;
  --el-button-disabled-border-color: var(--primary);
  --el-button-disabled-text-color: var(--primary);
}

.user-nickname {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary, #374151);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.3;
  text-align: right;
}

.logout-btn {
  color: var(--text-secondary, #6b7280);
  padding: 4px 8px;
}

.logout-btn:hover {
  color: var(--el-color-danger);
}

.list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.list.locked .item:not(.active) {
  cursor: not-allowed;
}

.item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition:
    background var(--transition-fast),
    transform var(--transition-fast),
    box-shadow var(--transition-fast);
}

.item:hover:not(.disabled) {
  background: var(--bg-hover);
  transform: translateX(2px);
}

.item.active {
  background: var(--bg-active);
  box-shadow: inset 3px 0 0 var(--primary);
}

.item.disabled {
  opacity: 0.7;
}

.item .icon {
  color: var(--primary);
  font-size: 18px;
}

.item .meta {
  flex: 1;
  min-width: 0;
}

.item .name {
  font-size: 13.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.name-edit {
  display: block;
  width: 100%;
  box-sizing: border-box;
  font-size: 13.5px;
  font-family: inherit;
  font-weight: 500;
  color: var(--text-primary);
  background: transparent;
  border: none;
  border-bottom: 1px solid transparent;
  border-radius: 0;
  padding: 0 0 1px;
  margin: 0;
  outline: none;
}

.name-edit:focus {
  border-bottom-color: var(--primary);
}

.item .sub {
  font-size: 11.5px;
  color: var(--text-secondary);
  margin-top: 2px;
}

.item .del {
  opacity: 0;
  color: var(--el-color-danger);
  transition: opacity 0.12s;
}

.item:hover .del {
  opacity: 1;
}

.edit-btn {
  opacity: 0;
  color: var(--text-secondary);
  transition: opacity 0.12s;
}
.item:hover .edit-btn {
  opacity: 1;
}
.item.editing .edit-btn {
  opacity: 1;
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
  gap: 6px;
  min-height: 36px;
}

.streaming-tip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--primary);
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
