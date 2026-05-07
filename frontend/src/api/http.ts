import { useAuthStore } from '@/store/auth'
import { router } from '@/router'

const API_BASE = (import.meta.env.VITE_API_BASE ?? '').replace(/\/$/, '')

export function apiUrl(path: string): string {
  return `${API_BASE}${path}`
}

/**
 * 带认证的 fetch 封装。
 * 自动附加 X-Auth-Token，并在收到 401 时清除登录态并跳转到登录页。
 */
export async function authFetch(path: string, init?: RequestInit): Promise<Response> {
  const auth = useAuthStore()
  const token = auth.getToken()

  const headers = new Headers(init?.headers)
  if (token) {
    headers.set('X-Auth-Token', token)
  }

  const resp = await fetch(apiUrl(path), { ...init, headers })

  if (resp.status === 401 && router.currentRoute.value.path !== '/login') {
    auth.clearSession()
    await router.replace('/login')
  }

  return resp
}
