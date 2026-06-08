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
}

/** 与后端 SessionInfo 对应（注意字段叫 updatedAt 不是 lastUpdatedAt）。 */
export interface SessionInfo {
  sessionId: string
  title: string
  messageCount: number
  updatedAt: number
}
