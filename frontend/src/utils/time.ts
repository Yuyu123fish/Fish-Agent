/**
 * 把毫秒时间戳格式化成"刚刚 / N 分钟前 / 今天 HH:mm / 昨天 HH:mm / yyyy-MM-dd"。
 *
 * 用于会话列表/消息附注等需要快速识别"多久之前"的场景。
 */
export function formatRelativeTime(ts?: number | null): string {
  if (!ts || ts <= 0) return ''
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ''

  const now = Date.now()
  const diff = now - ts

  if (diff < 0) {
    // 未来时间（机器时钟漂移），直接显示具体时间
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  }
  if (diff < 60_000) return '刚刚'
  if (diff < 60 * 60_000) return `${Math.floor(diff / 60_000)} 分钟前`

  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const yesterday = new Date(today.getTime() - 24 * 60 * 60_000)

  const hhmm = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })

  if (ts >= today.getTime()) return `今天 ${hhmm}`
  if (ts >= yesterday.getTime()) return `昨天 ${hhmm}`

  const sameYear = d.getFullYear() === new Date().getFullYear()
  return sameYear
    ? d.toLocaleDateString([], { month: '2-digit', day: '2-digit' })
    : d.toLocaleDateString()
}
