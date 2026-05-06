# Fish-Agent 项目简历材料

> 适用方向：后端开发 / 大模型应用开发 / AI Agent 工程
> 时间：2026-03 至今
> 代码规模：Java 约 103 文件，前端 TS/Vue 约 21 文件，Python Worker 约 19 文件

---

## Fish-Agent 智能体平台

**项目简介：** 基于 Spring AI Alibaba ReAct 的全栈 AI 智能助手系统。实现 ReAct 自主规划智能体、分层记忆（Redis 短期 + ES 长期）、三索引 RAG 检索引擎、SPI 工具插件化调用、知识库全链路闭环、Redis Lua 多维限流与鉴权体系。支持 DashScope / Ollama / DeepSeek 三路对话路由与嵌入独立选型，SSE 流式对话 + Vue 3 前端。

**技术栈：** Spring Boot 3.5 + Spring AI Alibaba + MyBatis-Plus + Redis (Lettuce + Lua) + Elasticsearch 8.x (dense_vector) + MinIO/RustFS + MySQL + Python (PyMuPDF + Tesseract OCR + tiktoken + httpx → DashScope) + Vue 3.5 + TypeScript + Vite 6 + Element Plus + Pinia + SSE

### ReAct 智能体 + 工具 SPI 插件体系

基于 Spring AI Alibaba `ReactAgent` 构建 ChatAgent，实现「思考-行动-观察」自主推理循环。三重防死循环机制：`CompileConfig.recursionLimit`（图节点切换硬上限）、`ModelCallLimitHook`（触达后优雅终止并追加 END 指令，避免额外 token 消耗）、`AgentStatus` 原子状态机（IDLE / RUNNING / FINISHED / ERROR，支持外部中断）。基于 SPI 机制设计 `AgentToolProvider` 接口 + `ToolRegistry` 启动期自动发现，内置 5 个工具（DateTime / Calculator / WebFetch / FileRead / FileWrite）+ 外部 5 个工具（Tavily / 博查搜索 / 高德天气 / 高德地理 / 邮件），外部工具按 `@ConditionalOnProperty` 懒装配，单工具构造失败不影响其他工具注册，运行时异常不传播到 Agent 主循环。

### 三路模型路由 + 对话/嵌入独立配置

`FishLlmEnvironmentPostProcessor`（`EnvironmentPostProcessor`，`Ordered.LOWEST_PRECEDENCE`）在 Spring 环境早期将 `fish.llm.chat-provider` 自动推导为 `spring.ai.model.chat`，覆盖 application.yml 但低于 `-D` / 环境变量。DeepSeek 通过复用 OpenAI 兼容适配器零代码接入——仅需改 `base-url` 从 `api.openai.com` 到 `api.deepseek.com`，整条 OpenAiChatModel 自动配置栈复用。Chat 与 Embedding 完全解耦：`fish.llm.chat-provider` 管对话，`fish.llm.embedding.provider` 管嵌入，可自由组合（如 Chat 用 DeepSeek、Embedding 用 DashScope）。`@Primary` ChatModel 收敛，`@Qualifier("memoryChatModel")` 独立记忆模型 Bean 支持记忆压缩与事实抽取使用更低 temperature 的专用模型。

### 分层记忆体系：Redis ZSET 短期 + ES dense_vector 长期

三层记忆按访问模式而非数据类型分层：短期记忆（Redis ZSET 滑动窗口 + LLM 摘要）在每轮对话开始时**同步**加载，延迟直接影响首字等待，必须是 Redis 微秒级；长期事实（ES `dense_vector`）跨会话累积，检索时**异步**注入，需全文 + 向量双路查询能力；RustFS/MinIO 对话 JSON 作为完整事实源持久化。短期压缩链路「只写 Redis」、长期抽取链路「只写 ES」，两条链路职责不交叉。`LongTermMemoryPromptBuilder` 约束 LLM 仅提取稳定用户事实，`FactSanitizer` 在写入前过滤产品能力介绍类误抽取，双保险保障向量索引信噪比。

### RAG 三索引双路并发检索

`fish-user-memory`（对话事实，`source_type=chat`）+ `fish-user-knowledge`（用户文档，PRIVATE）+ `fish-public-knowledge`（公共知识，PUBLIC）三索引完全分离，各自 DDL 独立演进。每条子查询 3 索引 × 2 路（文本 `match` + 向量 `knn`）= 6 路并发召回，JDK 21 虚拟线程执行。`UserContextHolder` 快照在异步线程中显式回放（入口 `get()` → lambda 内 `set(snapshot)` → finally `clear()`），避免 ThreadLocal 在虚拟线程 carrier thread 切换时丢失导致私有数据空集。结果按 score 去重合并后注入单条 SystemMessage，渲染上限控制（`max-injected-facts=8`，`max-injected-chars=4000`）。查询增强：可选 LLM 查询重写 + 多查询扩展拆解为最多 12 条子查询。

### 知识库全链路闭环：Java 投递 + Python Worker 异步消费

前端支持小文件直传（≤1MB 流式写入）与大文件分片上传（5MB/chunk，MinIO composeObject 合并）；Java 侧仅负责写入 RustFS + MySQL + Redis Stream（XADD），不解析文件内容。Python Worker 通过 `XREADGROUP` 消费者组异步消费，通过 PyMuPDF 渲染每页为 300 DPI PNG → Tesseract OCR（`chi_sim+eng` 中英双语）→ tiktoken `cl100k_base` 512 token 分块（50 overlap，按页不跨页）→ DashScope `text-embedding-v2` 批量 embedding（1536 维）→ ES bulk 写入（20 条/批）。PDF 解析经过 pypdf → pdfminer → PyMuPDF + OCR 三代库迭代，用「看见→认出」替代「解码→乱码」，中文准确率 >95%。三重崩溃保障：XAUTOCLAIM（120s idle 自动认领超时消息）+ Python `delete_by_doc_id` 幂等（重处理前清空旧切片）+ Java `OrphanTaskCompensationService`（`@Scheduled` 每 60s 扫描超时 PROCESSING 记录并标记 FAILED）。Worker 消费循环实现三级优先级拉取：优先重试自身 PEL 未 ACK 消息 → 其次 XAUTOCLAIM 认领 → 最后读新消息；finally 块保证无论成败都 XACK，杜绝毒消息死循环。

### Redis Lua 三层限流 + 会话互斥

仅对 `/api/chat/**` 生效：令牌桶（惰性 refill，请求驱动计算，不用定时器）+ SSE 并发槽（Lua 原子 INCR + 超限自动 DECR 回滚）+ 会话互斥锁（`SET NX EX 120`，防同 sessionId 并发竞态）。429 / 409 精确区分频率拒绝与资源冲突。所有释放逻辑（SSE DECR + 会话锁 DEL + `disposable.dispose()`）合并到 `releaseSseSlotOnce`（`AtomicBoolean` 幂等），关键修复：`emitter` 生命周期回调在 `subscribe()` **之前**注册——若 Reactor 的 `onComplete` 异步触发 `emitter.complete()` 时回调尚未注册，锁永久泄漏导致该 session 后续所有对话全被阻塞。Redis 异常时 fail-open 放行，避免限流成为单点故障。

### 鉴权与多租户数据隔离

自定义 UUID Token + Redis Session：`BCryptPasswordEncoder` 密码哈希，登录时生成 32 字符 token → `SETEX fish:session:{token} 86400 UserContext JSON`；`GlobalAuthInterceptor`（`HandlerInterceptor`，非 Filter）拦截 `/api/**` 校验 `X-Auth-Token`，白名单放行 `/api/auth/**`，每次请求自动 TTL 续期。ES 三索引中私有数据强制 `user_id` term filter + `source_type=chat` 防御性约束（防止文档切片误写入记忆索引后被召回）。`PermissionInterceptor` 预留 ADMIN 角色权限细分。

### 全栈 SSE 流式协议

后端 `SseEmitter`（5 分钟超时）订阅 `Flux<NodeOutput>`，推送 `session / chunk / tool / done / error` 五类事件，工具调用过程前端可视化。前端 `Fetch + ReadableStream` 逐 chunk 增量追加 + `marked` 实时渲染 Markdown + `highlight.js` 代码高亮，`AbortController` 支持用户中途取消，后端感知 `IOException` 后终止 Agent 推理。17 项 CSS 变量驱动暗夜模式，`main.ts` 在 `createApp` 前初始化主题防首帧闪白。

### Python Worker 工程实践

- `pydantic-settings` 配置与 Java `application.yml` 环境变量命名体系完全对齐，一份 `.env` 两端共用
- `threading.local()` 每线程独立复用 MySQL 连接并 `ping(reconnect=True)` 自动重连
- Docker 多阶段构建，含 Tesseract OCR 中文语言包，HEALTHCHECK 每 30s 探活
- 水平扩展：多实例共享同一消费者组，Redis 自动分发消息到不同 consumer

### 防御性编程细节

- `UserMemorySearcher` 加 `source_type=chat` 防御 filter，即使历史 `source_type=document` 误写入也不会在记忆检索中被召回
- Python `consumer.py` 无论成功/失败/非法消息都 XACK，避免毒消息 PEL 死循环
- 删除操作按 ES → RustFS → MySQL 顺序「尽力清理」，每步失败只记日志不抛异常，避免孤儿资源
- 前端分片上传失败自动调 `abortMultipartUpload` 清理 MinIO 残留分片
- `ChatService` emitter 回调在 subscribe 之前注册：若 Reactor onComplete 异步触发 emitter.complete() 时回调尚未注册，`releaseSseSlotOnce` 永久不执行，导致会话锁泄漏
- 空消息与组装异常路径主动 DECR SSE 槽位，防槽位泄漏
