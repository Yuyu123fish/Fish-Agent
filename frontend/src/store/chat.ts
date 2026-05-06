import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  deleteSession as apiDeleteSession,
  getHistory,
  listSessions,
  streamChat
} from '@/api/chat'
import type { ChatMessage, SessionInfo } from '@/types/chat'

/**
 * 会话与消息状态中心。
 *
 * 关注点：
 *  - 单一活跃会话：activeSid。切换会话时按需懒加载历史。
 *  - 流式追加：当前流期间把增量 chunk 拼到末尾的 assistant 气泡，懒生成。
 *  - 中止：abortController 持有当前请求，UI 上"停止生成"调 cancel。
 */
export const useChatStore = defineStore('chat', () => {
  const sessions = ref<SessionInfo[]>([])
  const activeSid = ref<string>('')
  const messagesBySid = ref<Record<string, ChatMessage[]>>({})
  const streaming = ref(false)
  const errorMsg = ref<string>('')

  let abortController: AbortController | null = null

  const messages = computed<ChatMessage[]>(() => messagesBySid.value[activeSid.value] ?? [])

  async function refreshSessions(): Promise<void> {
    try {
      sessions.value = await listSessions()
    } catch (e: any) {
      errorMsg.value = e?.message ?? '加载会话列表失败'
    }
  }

  async function selectSession(sid: string): Promise<void> {
    if (streaming.value) return
    activeSid.value = sid
    if (!sid) return
    if (messagesBySid.value[sid]) return
    try {
      const history = await getHistory(sid)
      messagesBySid.value = { ...messagesBySid.value, [sid]: history }
    } catch (e: any) {
      errorMsg.value = e?.message ?? '加载历史失败'
    }
  }

  function newSession(): void {
    if (streaming.value) return
    activeSid.value = ''
    errorMsg.value = ''
  }

  async function deleteSession(sid: string): Promise<void> {
    if (streaming.value) return
    try {
      await apiDeleteSession(sid)
      delete messagesBySid.value[sid]
      if (activeSid.value === sid) activeSid.value = ''
      await refreshSessions()
    } catch (e: any) {
      errorMsg.value = e?.message ?? '删除失败'
    }
  }

  function appendMessage(sid: string, msg: ChatMessage): void {
    const list = messagesBySid.value[sid] ? [...messagesBySid.value[sid]] : []
    list.push(msg)
    messagesBySid.value = { ...messagesBySid.value, [sid]: list }
  }

  /** 在末尾保证存在一条 assistant 占位气泡；若末尾不是 assistant 则新建。 */
  function ensureAssistantTail(sid: string): void {
    const list = messagesBySid.value[sid]
    if (!list || list.length === 0 || list[list.length - 1].role !== 'assistant') {
      appendMessage(sid, { role: 'assistant', content: '', createdAt: Date.now() })
    }
  }

  function updateLastAssistant(sid: string, mutate: (msg: ChatMessage) => void): void {
    const list = messagesBySid.value[sid]
    if (!list || list.length === 0) return
    const last = list[list.length - 1]
    if (last.role !== 'assistant') return
    mutate(last)
    messagesBySid.value = { ...messagesBySid.value, [sid]: [...list] }
  }

  /** 移除末尾内容为空的 assistant 气泡（流结束/出错后清理"正在思考…"占位）。 */
  function trimEmptyAssistantTail(sid: string): void {
    const list = messagesBySid.value[sid]
    if (!list || list.length === 0) return
    const last = list[list.length - 1]
    if (last.role === 'assistant' && (!last.content || !last.content.trim())) {
      const next = list.slice(0, -1)
      messagesBySid.value = { ...messagesBySid.value, [sid]: next }
    }
  }

  async function send(text: string): Promise<void> {
    const content = text.trim()
    if (!content || streaming.value) return
    errorMsg.value = ''

    // sessionId 为空时由后端在响应里通过 event: session 推回真实 sid
    const apiSid = activeSid.value
    const pendingSid = apiSid || '__pending__'
    // 新会话立即切到占位 sid，让 messages computed 能读到刚写入的气泡
    if (!apiSid) {
      activeSid.value = '__pending__'
    }
    if (!messagesBySid.value[pendingSid]) {
      messagesBySid.value[pendingSid] = []
    }
    appendMessage(pendingSid, { role: 'user', content, createdAt: Date.now() })
    appendMessage(pendingSid, { role: 'assistant', content: '', createdAt: Date.now() })

    streaming.value = true
    abortController = new AbortController()

    // 保持 '' 表示「尚未拿到后端真实 sid」，供 onSession 的 guard 使用
    let assignedSid = apiSid

    try {
      await streamChat(
        { sessionId: apiSid, message: content, signal: abortController.signal },
        {
          onSession: (sid) => {
            if (!assignedSid) {
              assignedSid = sid
              if (pendingSid !== sid && messagesBySid.value[pendingSid]) {
                messagesBySid.value[sid] = messagesBySid.value[pendingSid]
                delete messagesBySid.value[pendingSid]
              }
              activeSid.value = sid
            }
          },
          onChunk: (delta) => {
            const sid = activeSid.value
            // 懒占位：若末尾不是 assistant（如刚出现过 tool 气泡），先补一条
            ensureAssistantTail(sid)
            updateLastAssistant(sid, (m) => {
              m.content += delta
            })
          },
          onTool: (name, payload) => {
            const sid = activeSid.value
            // 工具调用前若当前 assistant 气泡仍是空（tool 在第一段输出之前发生），先把它干掉
            trimEmptyAssistantTail(sid)
            appendMessage(sid, {
              role: 'tool',
              toolName: name,
              content: payload ?? '',
              createdAt: Date.now()
            })
            // 注意：不再立刻 push 空 assistant 气泡，
            // 后续 chunk 来时由 ensureAssistantTail 懒生成。
          },
          onError: (msg) => {
            errorMsg.value = msg
          }
        }
      )
    } finally {
      const sid = assignedSid || pendingSid
      // 收尾：若末尾仍是空 assistant（出错 / 取消 / 模型只调工具不回话），删掉避免界面悬挂"正在思考…"
      trimEmptyAssistantTail(sid)
      // 流失败且从未拿到真实 sid：清理占位，回到欢迎态
      if (!assignedSid) {
        delete messagesBySid.value['__pending__']
        activeSid.value = ''
      }
      streaming.value = false
      abortController = null
      // 流结束后刷新一次会话列表（更新 updatedAt / 标题）
      await refreshSessions()
    }
  }

  function cancel(): void {
    abortController?.abort()
    abortController = null
    streaming.value = false
  }

  return {
    sessions,
    activeSid,
    messages,
    messagesBySid,
    streaming,
    errorMsg,
    refreshSessions,
    selectSession,
    newSession,
    deleteSession,
    send,
    cancel
  }
})
