# Fish-Agent 项目简历材料

> 适用方向：后端开发 / 大模型应用开发 / AI Agent 工程
> 时间：2026-03 至今
> 代码规模：Java 约 103 文件，前端 TS/Vue 约 21 文件，Python Worker 约 19 文件

---

## 项目概述（简历一行总结）

基于 Spring AI Alibaba ReAct 的全栈 AI 智能助手平台，实现自主规划智能体、分层记忆与 RAG 检索、三路模型路由、知识库 OCR 闭环、Redis Lua 限流鉴权体系，支持 SSE 流式对话与 Vue 3 前端。

**技术栈：** Spring Boot 3.5.13 + Spring AI Alibaba 1.1.2 + MyBatis-Plus 3.5 + Redis (Lettuce + Lua) + Elasticsearch 8.x (dense_vector + kNN) + MinIO/RustFS + MySQL + JDK 21 虚拟线程 + Python 3.11 (PyMuPDF + Tesseract OCR + tiktoken + DashScope Embedding) + Vue 3.5 + TypeScript + Vite 6 + Element Plus + Pinia + SSE

---

## 简历要点（可直接用于简历 bullet points）

### 智能体与工具链
- 基于 Spring AI Alibaba `ReactAgent` 构建「思考-行动-观察」自主推理循环，实现多步推理与工具调用失败后自主切换策略
- 设计 `AgentToolProvider` SPI 插件接口 + `ToolRegistry` 启动期自动发现，内置 5 个工具 + 外部 5 个工具按 `@ConditionalOnProperty` 懒装配，单工具构造失败不影响 Agent 主循环
- 三重防死循环机制：`recursionLimit`（图节点硬上限）+ `ModelCallLimitHook`（优雅终止并追加 END 指令，避免额外 token 消耗）+ `AgentStatus` 原子状态机（支持外部中断）

### 三路模型路由
- 通过 `EnvironmentPostProcessor`（`Ordered.LOWEST_PRECEDENCE`）在 Spring 启动早期将业务枚举 `fish.llm.chat-provider` 自动推导为 `spring.ai.model.chat`，切换模型只需一个环境变量
- DeepSeek 通过复用 OpenAI 兼容适配器零代码接入——仅改 `base-url`，整条 `OpenAiChatModel` 自动配置栈复用
- Chat 与 Embedding 完全解耦，可自由组合（如 Chat 用 DeepSeek、Embedding 用 DashScope）；独立 `memoryChatModel` Bean 支持记忆压缩与事实抽取使用更低 temperature 的专用模型

### 分层记忆体系
- 三层记忆按访问模式分层：Redis ZSET 滑动窗口（短期，同步加载，微秒级）+ ES `dense_vector` 向量索引（长期事实，异步注入）+ RustFS/MinIO 对话 JSON（完整事实源持久化）
- 短期压缩链路「只写 Redis」、长期抽取链路「只写 ES」，两条链路职责不交叉，避免摘要重复灌入向量索引
- `LongTermMemoryPromptBuilder` 约束 LLM 仅提取稳定用户事实，`FactSanitizer` 写入前过滤误抽取，双保险保障向量索引信噪比

### RAG 三索引双路并发检索
- `fish-user-memory` + `fish-user-knowledge` + `fish-public-knowledge` 三索引完全分离，各自 DDL 独立演进
- 每条子查询 3 索引 × 2 路（文本 `match` + 向量 `knn`）= 6 路并发召回，JDK 21 虚拟线程执行
- `UserContextHolder` 快照在异步线程中显式回放，避免 ThreadLocal 在虚拟线程 carrier thread 切换时丢失导致私有数据空集
- 结果按 score 去重合并后注入单条 SystemMessage，渲染上限控制（max-injected-facts=8, max-injected-chars=4000）

### 知识库全链路闭环（Java 投递 + Python Worker 异步消费）
- 前端支持 ≤1MB 流式直传与 >1MB 分片上传（5MB/chunk，MinIO composeObject 合并）；Java 侧仅负责写入 RustFS + MySQL + Redis Stream（XADD），不解析文件内容
- Python Worker 通过 `XREADGROUP` 消费者组异步消费，PyMuPDF 渲染每页为 300 DPI PNG → Tesseract OCR (`chi_sim+eng` 中英双语) → tiktoken `cl100k_base` 512 token 分块（50 overlap，按页不跨页）→ DashScope `text-embedding-v2` 批量 embedding（1536 维）→ ES bulk 写入（20 条/批）
- PDF 解析经过 pypdf → pdfminer → PyMuPDF + OCR 三代库迭代，用「看见→认出」替代「解码→乱码」，中文准确率 >95%
- 三重崩溃保障：XAUTOCLAIM（120s idle 自动认领超时消息）+ Python `delete_by_doc_id` 幂等（重处理前清空旧切片）+ Java `OrphanTaskCompensationService`（`@Scheduled` 每 60s 扫描超时 PROCESSING 记录并标记 FAILED）

### Redis Lua 三层限流 + 会话互斥
- 仅对 `/api/chat/**` 生效：令牌桶（惰性 refill，请求驱动计算，不用定时器）+ SSE 并发槽（Lua 原子 INCR + 超限自动 DECR 回滚）+ 会话互斥锁（`SET NX EX 120`，防同 sessionId 并发竞态）
- 429 / 409 精确区分频率拒绝与资源冲突
- 所有释放逻辑（SSE DECR + 会话锁 DEL + `disposable.dispose()`）合并到 `releaseSseSlotOnce`（`AtomicBoolean` 幂等），emitter 回调在 `subscribe()` 之前注册——防止 Reactor 的 `onComplete` 异步触发时回调尚未注册导致锁永久泄漏

### 鉴权与多租户数据隔离
- 自定义 UUID Token + Redis Session：`BCryptPasswordEncoder` 密码哈希，登录生成 32 字符 token → `SETEX fish:session:{token} 86400 UserContext JSON`
- `GlobalAuthInterceptor`（`HandlerInterceptor`，非 Filter）拦截 `/api/**` 校验 `X-Auth-Token`，白名单放行 `/api/auth/**`，每次请求自动 TTL 续期
- ES 三索引中私有数据强制 `user_id` term filter + `source_type=chat` 防御性约束（防止文档切片误写入记忆索引后被召回）

### 全栈 SSE 流式对话
- 后端 `SseEmitter`（5 分钟超时）订阅 `Flux<NodeOutput>`，推送 `session / chunk / tool / done / error` 五类事件
- 前端 `Fetch + ReadableStream` 逐 chunk 增量追加 + `marked` 实时渲染 Markdown + `highlight.js` 代码高亮，`AbortController` 支持用户中途取消
- 17 项 CSS 变量驱动暗夜模式，`main.ts` 在 `createApp` 前初始化主题防首帧闪白

### 工程实践亮点
- Python Worker 使用 `pydantic-settings` 配置，与 Java `application.yml` 环境变量命名体系完全对齐，一份 `.env` 两端共用
- `threading.local()` 每线程独立复用 MySQL 连接并 `ping(reconnect=True)` 自动重连
- Docker 多阶段构建，含 Tesseract OCR 中文语言包，HEALTHCHECK 每 30s 探活
- Python 消费循环实现三级优先级拉取：优先重试自身 PEL 未 ACK 消息 → 其次 XAUTOCLAIM 认领 → 最后读新消息
- `finally` 块保证无论成败都 XACK，杜绝毒消息死循环
- 删除操作按 ES → RustFS → MySQL 顺序「尽力清理」，每步失败只记日志不抛异常，避免孤儿资源
- 前端分片上传失败自动调 `abortMultipartUpload` 清理 MinIO 残留分片
- `UserMemorySearcher` 加 `source_type=chat` 防御 filter，即使历史误写入也不被召回

---

## 面试讨论要点（面试中可能展开的深度话题）

### 1. 为什么用 ReAct 而不是纯 Function Calling？
ReAct 的「观察→调整」循环给模型一次重试机会——搜索 API 超时、网页不可达时模型可自动换策略。如果换成纯 function calling，调用方需要手写 while 循环 + 重试策略 + 异常处理，本质上是在重新实现一个更差版本的 ReAct。Spring AI Alibaba 已提供成熟的 graph 执行引擎 + 流式 + Hook + 状态机。

### 2. 为什么短期用 Redis、长期用 ES？
分层依据是访问模式而非数据类型：短期窗口在每轮对话开始时同步加载，延迟直接影响首字等待，必须是 Redis 微秒级；长期事实跨会话累积、检索时异步注入，EL 毫秒级可接受，且需要全文 + 向量双路查询能力。

### 3. 为什么三索引分离而不是一个混合索引？
三个索引的权限模型不同（记忆/文档是私有 `user_id` filter，公共是全局无 filter）、写入方不同（Java vs Python Worker）、DDL 独立演进（分片数、刷新间隔可分别调整）。混在一起每次检索都要带复杂 filter 组合。

### 4. 为什么选择 ES kNN 而不是 Milvus/Pinecone？
当前量级（数十万向量）ES kNN 完全够用。Milvus 需要独立部署 4-5 个组件，Pinecone 需出站且额外付费。项目中 ES 已承担全文检索（`ik_max_word` 分词），一个引擎搞定文本 + 向量双路召回，不引入额外中间件。

### 5. 为什么用 Redis Stream 而不是 Kafka/RabbitMQ？
项目已有 Redis（Session、短期记忆、限流），Stream 零额外中间件。消费者组 + PEL + XAUTOCLAIM 提供与 Kafka 同级的消息可靠性。`finally` 中无论成败都 XACK + `delete_by_doc_id` 幂等保护，毒消息不会死循环。

### 6. 为什么自定义 token + Redis Session 而不是 JWT？
JWT 的无状态特性在需要即时封禁/登出/角色变更时反成劣势，必须配合黑名单退化为 Redis Session。UUID token 作为随机 key，Redis `GET` 微秒级，`DEL` 即时登出。行为简单透明——排障和审计时任何人都能看懂。

### 7. 虚拟线程下 ThreadLocal 丢失怎么解决？
`CompletableFuture.runAsync()` 中虚拟线程可能切换 carrier thread，ThreadLocal 不保证传递。解决：入口 `UserContextHolder.get()` 快照到局部变量 → 异步 lambda 内显式 `set(snapshot)` → finally `clear()`。代码量多三行，行为完全可控，不依赖框架特性。

### 8. emitter 回调注册顺序为什么是关键？
`SseEmitter` 每个生命周期事件只能注册一个回调。若 subscribe 先于回调注册，Reactor 的 `onComplete` 可能异步触发 `emitter.complete()`，此时 `onCompletion` 尚未注册 → 回调永久丢失 → `releaseSseSlotOnce` 不执行 → 会话锁泄漏，该 session 后续所有对话全被阻塞。修复：回调在 subscribe 之前注册，Disposable 通过数组引用桥接。

---

## 项目接口速查

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录，返回 token |
| POST | `/api/auth/logout` | 登出 |
| GET | `/api/auth/me` | 当前用户信息 |
| POST | `/api/chat/stream` | SSE 流式对话（限流可返回 429/409） |
| GET | `/api/chat/sessions` | 会话列表 |
| GET | `/api/chat/sessions/{sid}` | 会话历史 |
| DELETE | `/api/chat/sessions/{sid}` | 删除会话 |
| POST | `/api/knowledge/upload` | 文档直传（≤1MB） |
| POST | `/api/knowledge/upload/init` | 分片上传初始化 |
| POST | `/api/knowledge/upload/chunk` | 上传分片 |
| POST | `/api/knowledge/upload/complete` | 完成分片合并 |
| GET | `/api/knowledge/tasks/{taskId}` | 轮询解析状态 |
| GET | `/api/knowledge/documents` | 文档列表（分页） |
| DELETE | `/api/knowledge/documents/{taskId}` | 删除文档 |

SSE 事件类型：`session` / `chunk` / `tool` / `done` / `error`

---

_文档版本：基于 v2 全景（v2.0 → v2.5）· 更新日期：2026-05-07_
