import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { LoginResponse } from '@/types/auth'

const TOKEN_KEY = 'fish-agent-token'
const NICKNAME_KEY = 'fish-agent-nickname'

function readStoredNickname(): string | null {
  const raw = localStorage.getItem(NICKNAME_KEY)
  if (raw == null || raw === '' || raw === 'null') {
    return null
  }
  const t = raw.trim()
  return t.length > 0 ? t : null
}

/**
 * 登录态：token 持久化到 localStorage，供 fetch 与路由守卫使用。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const userId = ref<number | null>(null)
  const nickname = ref<string | null>(readStoredNickname())
  const role = ref<string | null>(null)

  const isLoggedIn = computed(() => !!token.value)

  /**
   * 登录或注册成功后写入会话令牌。
   */
  function setSession(t: string, uid: number, nick: string | null | undefined, r: string) {
    token.value = t
    userId.value = uid
    const safeNick = (nick ?? '').trim()
    nickname.value = safeNick.length > 0 ? safeNick : null
    role.value = r
    localStorage.setItem(TOKEN_KEY, t)
    if (safeNick.length > 0) {
      localStorage.setItem(NICKNAME_KEY, safeNick)
    } else {
      localStorage.removeItem(NICKNAME_KEY)
    }
  }

  /**
   * 用 /me 等接口回填 profile，不写 token。
   */
  function applyProfile(me: LoginResponse) {
    userId.value = me.userId
    const safeNick = (me.nickname ?? '').trim()
    nickname.value = safeNick.length > 0 ? safeNick : null
    role.value = me.role ?? null
    if (safeNick.length > 0) {
      localStorage.setItem(NICKNAME_KEY, safeNick)
    } else {
      localStorage.removeItem(NICKNAME_KEY)
    }
  }

  /**
   * 清除本地会话（退出登录）。
   */
  function clearSession() {
    token.value = null
    userId.value = null
    nickname.value = null
    role.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(NICKNAME_KEY)
  }

  /**
   * 供非组件模块读取当前 token（如 api/chat.ts）。
   */
  function getToken(): string | null {
    return token.value ?? localStorage.getItem(TOKEN_KEY)
  }

  return { token, userId, nickname, role, isLoggedIn, setSession, applyProfile, clearSession, getToken }
})
