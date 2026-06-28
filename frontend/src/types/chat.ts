/**
 * 与后端 DTO 对齐的类型定义。
 *
 * 与后端 {@code com.yuyu.fishagent.dto.ChatMessageDTO} 保持字段一致：
 *  - {@code role}：角色字符串。
 *  - {@code content}：消息正文。
 *  - {@code createdAt}：毫秒时间戳（后端 long）。
 */
export interface ChatMessage {
  role: 'user' | 'assistant' | 'system' | 'tool'
  content: string
  createdAt?: number
  /**
   * 工具气泡专用，后端持久化结构里没有此字段；
   * 前端在 SSE `event: tool` 收到时本地构造，仅用于展示。
   */
  toolName?: string
  /**
   * 答案出处引用 [v6.4]：后端持久化结构里没有此字段；
   * 前端在 SSE `event: sources` 收到时本地构造，仅用于展示。
   */
  sources?: SourceRef[]
}

/**
 * 答案出处引用 [v6.4]，与后端 {@code com.yuyu.fishagent.chat.dto.SourceRef} 对齐。
 * - memory=true：对话记忆源，timeText 为相对年龄（如"3天前"），无 docId。
 * - memory=false：文档/卡片源，docId+chunkIndex 可跳回原文，timeText 为 yyyy-MM。
 */
export interface SourceRef {
  label: string
  /** 来源分类 [v6.4]，前端按此分组 */
  kind?: 'MEMORY' | 'DOC' | 'CARD' | 'PUBLIC'
  docId?: string | null
  chunkIndex?: number | null
  snippet: string
  memory: boolean
  timeText: string
}

/** 与后端 SessionInfo 对应（注意字段叫 updatedAt 不是 lastUpdatedAt）。 */
export interface SessionInfo {
  sessionId: string
  title: string
  messageCount: number
  updatedAt: number
}
