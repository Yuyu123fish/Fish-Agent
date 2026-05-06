import type { LoginResponse } from '@/types/auth'
import { useAuthStore } from '@/store/auth'

const API_BASE = (import.meta.env.VITE_API_BASE ?? '').replace(/\/$/, '')

function url(path: string): string {
  return `${API_BASE}${path}`
}

/**
 * 注册并返回登录态。
 */
export async function register(username: string, password: string, nickname?: string): Promise<LoginResponse> {
  const r = await fetch(url('/api/auth/register'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, nickname })
  })
  const data = await r.json()
  if (!r.ok) {
    throw new Error(data?.message ?? `HTTP ${r.status}`)
  }
  return data as LoginResponse
}

/**
 * 登录。
 */
export async function login(username: string, password: string): Promise<LoginResponse> {
  const r = await fetch(url('/api/auth/login'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  })
  const data = await r.json()
  if (!r.ok) {
    throw new Error(data?.message ?? `HTTP ${r.status}`)
  }
  return data as LoginResponse
}

/**
 * 服务端注销会话。
 */
export async function logoutApi(): Promise<void> {
  const auth = useAuthStore()
  const t = auth.getToken()
  await fetch(url('/api/auth/logout'), {
    method: 'POST',
    headers: {
      ...(t ? { 'X-Auth-Token': t } : {})
    }
  })
}

/**
 * 拉取当前用户概要（需已登录）。
 */
export async function fetchMe(): Promise<LoginResponse> {
  const auth = useAuthStore()
  const t = auth.getToken()
  const r = await fetch(url('/api/auth/me'), {
    headers: {
      ...(t ? { 'X-Auth-Token': t } : {})
    }
  })
  const data = await r.json()
  if (!r.ok) {
    throw new Error(data?.message ?? `HTTP ${r.status}`)
  }
  return data as LoginResponse
}
