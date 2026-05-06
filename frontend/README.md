# Fish Agent Frontend

Vue 3 + Vite + TypeScript + Element Plus + Pinia 实现的 Fish-Agent 对话前端。

## 启动

```bash
pnpm install
pnpm dev          # 起在 http://127.0.0.1:5173
```

`vite.config.ts` 已把 `/api/**` 反代到 `http://localhost:8080`，请保证后端先启动。

## 与后端的 SSE 协议

后端 `POST /api/chat/stream` 返回 `text/event-stream`，前端按以下事件类型消费（见 [`src/api/chat.ts`](src/api/chat.ts)）：

| event | data |
|---|---|
| `session` | 服务端为新会话分配的 sessionId（仅首次会话回包） |
| `chunk`   | LLM token 增量（拼接到当前 assistant 气泡） |
| `tool`    | 工具调用提示，建议是 `{"name":"xxx","payload":"..."}` JSON |
| `done`    | 流正常结束 |
| `error`   | 错误信息（文本） |

## 目录结构

```
src/
├── api/chat.ts            HTTP/SSE 客户端
├── store/chat.ts          Pinia store（会话 / 消息 / 流式追加）
├── types/chat.ts          与后端 DTO 对齐的类型
├── components/            原子组件（侧栏 / 消息流 / 气泡 / 输入框）
├── views/ChatView.vue     主布局
├── App.vue
├── main.ts
└── style.css
```
