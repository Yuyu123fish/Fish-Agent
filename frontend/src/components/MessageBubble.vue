<script setup lang="ts">
import { computed, ref } from 'vue'
import { marked } from 'marked'
import hljs from 'highlight.js'
import { ElMessage } from 'element-plus'
import { Tools, DocumentCopy, Check, ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import type { ChatMessage } from '@/types/chat'

const props = defineProps<{
  msg: ChatMessage
  /** 是否是消息列表的最后一条（用于流式光标判断） */
  isLast?: boolean
  /** 当前是否处于流式中 */
  streaming?: boolean
}>()

marked.setOptions({
  breaks: true,
  gfm: true
})

const renderer = new marked.Renderer()
renderer.code = ({ text, lang }: { text: string; lang?: string }) => {
  const language = lang && hljs.getLanguage(lang) ? lang : 'plaintext'
  const out = hljs.highlight(text, { language }).value
  const label = language !== 'plaintext' ? language : ''
  return `<div class="code-block"><div class="code-header"><span class="code-lang">${label}</span></div><pre><code class="hljs language-${language}">${out}</code></pre></div>`
}

const isUser = computed(() => props.msg.role === 'user')
const isTool = computed(() => props.msg.role === 'tool')
const isAssistant = computed(() => !isUser.value && !isTool.value)

const html = computed(() => {
  if (!props.msg.content) return ''
  return marked.parse(props.msg.content, { renderer }) as string
})

/** tool payload 若是 JSON 字符串则美化显示，否则原样。 */
const toolPayload = computed(() => {
  if (!isTool.value) return ''
  const raw = props.msg.content
  if (!raw) return ''
  try {
    const obj = JSON.parse(raw)
    return JSON.stringify(obj, null, 2)
  } catch {
    return raw
  }
})

const toolExpanded = ref(false)

const friendlyToolName = computed(() => {
  const name = props.msg.toolName || 'unknown'
  const map: Record<string, string> = {
    knowledge_search: '知识库检索',
    web_search: '网页搜索',
    code_interpreter: '代码执行',
    memory_search: '记忆检索'
  }
  return map[name] || name
})

/** 是否给 assistant 气泡末尾追加流式光标。仅当：是最后一条 && 正在流 && 是 assistant。 */
const showCursor = computed(
  () => isAssistant.value && props.isLast === true && props.streaming === true
)

const copied = ref(false)
async function copy() {
  const text = isTool.value ? toolPayload.value : props.msg.content
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    ElMessage.warning('复制失败：浏览器剪贴板不可用')
  }
}
</script>

<template>
  <div class="row" :class="{ user: isUser, tool: isTool }">
    <div class="bubble" :class="{ user: isUser, tool: isTool, assistant: isAssistant }">
      <!-- 工具气泡 -->
      <template v-if="isTool">
        <div class="tool-card">
          <div class="tool-badge">
            <el-icon class="tool-icon"><Tools /></el-icon>
          </div>
          <div class="tool-body">
            <div class="tool-title" @click="toolExpanded = !toolExpanded">
              <span class="tool-name">{{ friendlyToolName }}</span>
              <el-icon class="caret">
                <ArrowDown v-if="toolExpanded" />
                <ArrowUp v-else />
              </el-icon>
            </div>
            <div v-if="toolExpanded && toolPayload" class="tool-payload">{{ toolPayload }}</div>
          </div>
        </div>
      </template>

      <!-- 用户气泡 -->
      <div v-else-if="isUser" class="plain">{{ msg.content }}</div>

      <!-- 助手气泡 -->
      <template v-else>
        <div
          v-if="msg.content"
          class="markdown-body"
          :class="{ streaming: showCursor }"
          v-html="html"
        />
        <div v-else class="placeholder">
          <span class="dot" />
          <span class="dot" />
          <span class="dot" />
        </div>
      </template>

      <!-- 复制按钮（仅非用户气泡 & 有内容时显示） -->
      <button
        v-if="!isUser && (msg.content || toolPayload)"
        class="copy-btn"
        :class="{ ok: copied }"
        @click="copy"
        :title="copied ? '已复制' : '复制'"
      >
        <el-icon>
          <Check v-if="copied" />
          <DocumentCopy v-else />
        </el-icon>
      </button>
    </div>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  margin: 10px 0;
  animation: bubble-in 0.25s ease-out;
}

.row.user {
  justify-content: flex-end;
}

.bubble {
  position: relative;
  max-width: 75%;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  word-break: break-word;
}

.bubble.user {
  background: var(--gradient-brand);
  color: var(--bubble-user-text);
  border-radius: var(--radius-md);
  box-shadow: 0 2px 8px rgba(79, 70, 229, 0.22);
  border-bottom-right-radius: 4px;
}

[data-theme='dark'] .bubble.user {
  box-shadow: 0 3px 16px rgba(99, 102, 241, 0.35);
}

.bubble.assistant {
  background: var(--bubble-assistant);
  border: none;
  color: var(--bubble-assistant-text);
  border-radius: var(--radius-md);
  border-bottom-left-radius: 4px;
  box-shadow: var(--shadow-sm);
}

.bubble.tool {
  background: var(--bg-main);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  max-width: 85%;
  padding: 12px 14px;
  box-shadow: var(--shadow-sm);
}

:deep(.code-block) {
  border-radius: var(--radius-md);
  overflow: hidden;
  margin: 8px 0;
  background: #1a1a2e;
}

:deep(.code-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 14px;
  background: rgba(255, 255, 255, 0.06);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

:deep(.code-lang) {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.5);
  font-family: 'JetBrains Mono', Consolas, monospace;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.tool-card {
  display: flex;
  gap: 10px;
  width: 100%;
}

.tool-badge {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  background: linear-gradient(135deg, rgba(79, 70, 229, 0.12), rgba(124, 58, 237, 0.08));
  display: flex;
  align-items: center;
  justify-content: center;
}

[data-theme='dark'] .tool-badge {
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.2), rgba(139, 92, 246, 0.15));
}

.tool-badge .tool-icon {
  font-size: 14px;
  color: var(--primary);
}

.tool-body {
  flex: 1;
  min-width: 0;
}

.tool-title {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
}

.tool-name {
  font-weight: 600;
  font-size: 13px;
  color: var(--text-primary);
}

.tool-title .caret {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-secondary);
}

.tool-payload {
  margin-top: 8px;
  padding: 8px 10px;
  background: var(--copy-btn-bg);
  border-radius: 6px;
  border-left: 2px solid var(--primary);
  font-size: 12px;
  line-height: 1.55;
  max-height: 240px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-secondary);
}

.plain {
  white-space: pre-wrap;
  line-height: 1.6;
}

/* "正在思考"三点跳动 */
.placeholder {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  height: 18px;
}
.placeholder .dot {
  width: 6px;
  height: 6px;
  background: var(--text-secondary);
  border-radius: 50%;
  opacity: 0.4;
  animation: blink 1.2s infinite;
}
.placeholder .dot:nth-child(2) {
  animation-delay: 0.2s;
}
.placeholder .dot:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes blink {
  0%,
  80%,
  100% {
    opacity: 0.25;
    transform: scale(0.9);
  }
  40% {
    opacity: 1;
    transform: scale(1);
  }
}

/* 流式光标：在 markdown 内容末尾绘制一个闪烁竖线 */
.markdown-body.streaming::after {
  content: '';
  display: inline-block;
  width: 3px;
  height: 16px;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--primary);
  border-radius: 2px;
  animation: caret 1s steps(2, start) infinite;
  opacity: 0.8;
}
@keyframes caret {
  to {
    visibility: hidden;
  }
}

/* 复制按钮 */
.copy-btn {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 26px;
  height: 26px;
  border-radius: 6px;
  border: 1px solid transparent;
  background: var(--copy-btn-bg);
  color: var(--text-secondary);
  cursor: pointer;
  opacity: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.12s ease, color 0.12s ease, background 0.12s ease;
  font-size: 14px;
}
.bubble:hover .copy-btn {
  opacity: 1;
}
.copy-btn:hover {
  color: var(--primary);
  background: var(--bg-main);
  border-color: var(--border);
}
.copy-btn.ok {
  opacity: 1;
  color: #16a34a;
  background: #ecfdf5;
  border-color: #bbf7d0;
}
[data-theme='dark'] .copy-btn.ok {
  color: #4ade80;
  background: rgba(74, 222, 128, 0.1);
  border-color: rgba(74, 222, 128, 0.25);
}
</style>
