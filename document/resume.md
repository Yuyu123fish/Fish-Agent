# Fish-Agent 项目简历材料

> 适用方向：后端开发 / 大模型应用开发
> 时间：2026-03 至今

---

## Fish-Agent 智能体平台

**项目简介：** 基于 Spring Boot 3.5 + Spring AI Alibaba 开发的全栈 AI 智能助手系统，集成 ReAct 自主规划智能体、三层记忆管理、三索引 RAG 检索、工具插件化调用以及知识库全链路闭环；支持 SSE 流式对话、多用户鉴权、文档 OCR 解析与向量化入库。

**技术栈：** SpringBoot + Spring AI Alibaba + MyBatis-Plus + Redis + Elasticsearch + MinIO(RustFS) + MySQL + Python(PyMuPDF / pytesseract / tiktoken) + Vue3 + TypeScript + SSE

- **ReAct 自主规划智能体：** 基于 Spring AI Alibaba ReAct 模式构建 ChatAgent，通过 AgentLoop 实现多步骤自主推理与工具调用；设计三重防死循环机制——recursionLimit（图节点切换硬上限）、ModelCallLimitHook（模型调用次数触达后优雅终止）、AgentStatus 原子状态机（IDLE / RUNNING / FINISHED / ERROR）互补保障，防止智能体陷入无限推理循环。

- **Tool Calling 扩展机制：** 基于 SPI 机制自主设计 AgentToolProvider 工具注册中心 + ToolRegistry 自动发现，内置 5 个工具（时间、计算器、网页抓取、文件读写），外部 5 个工具（Tavily/博查搜索、高德天气/地理、邮件）通过 @ConditionalOnProperty 按配置懒装配；新增工具仅需建类加 @Component，实现零侵入动态扩展。

- **三层记忆分层设计：** 文件/RustFS 对话历史（完整事实源）→ Redis ZSET 短期摘要 + 滑动窗口（控制上下文长度）→ Elasticsearch dense_vector 长期事实（可向量检索）；每层独立 SPI 接口，短期压缩链路「只写 Redis」、长期抽取链路「只写 ES」，两条链路职责不交叉，避免摘要重复写入向量索引。

- **三索引 RAG 检索引擎：** fish-user-memory（对话事实）+ fish-user-knowledge（用户私有文档）+ fish-public-knowledge（公共文档）三索引完全隔离；每次召回 3 索引 × 2 路（文本 match + kNN 向量）= 6 路并发检索，借助 JDK 21 虚拟线程执行；UserContextHolder 快照在异步线程中手动回放，避免 ThreadLocal 在虚拟线程调度中丢失导致私有数据空集；结果按 score 去重合并后注入单条 SystemMessage 上下文。

- **RAG 查询增强：** 实现查询重写拦截器（RagQueryRewrite），将模糊指代和省略问题补全为独立检索语句；引入多查询扩展机制（RagQueryExpand），结合虚拟线程并发检索，大幅降低多路查询向量数据库时的 I/O 阻塞，保障问答的针对性与流畅性。

- **长期记忆质量控制：** LongTermMemoryPromptBuilder 约束模型仅抽取稳定用户事实；FactSanitizer 在写入 ES 前过滤产品能力介绍类误抽取，双保险保障向量索引信噪比。

- **知识库全链路闭环：** 前端支持小文件直传（≤1MB 流式写入）与大文件分片上传（5MB/chunk，MinIO compose 合并）；Java 侧仅负责写入 RustFS + MySQL + Redis Stream，Python Worker 通过 XREADGROUP 消费者组异步消费——PyMuPDF 渲染每页为 300 DPI PNG → pytesseract OCR（chi_sim+eng 中英双语）→ tiktoken cl100k_base 512 token 分块（50 overlap，按页不跨页）→ DashScope 批量 embedding → ES bulk 写入；两进程仅共享基础设施，互不感知，XAUTOCLAIM 120s idle 自动崩溃恢复。

- **登录鉴权体系：** GlobalAuthInterceptor 拦截所有 /api/** 请求，校验 X-Auth-Token；RedisSessionManager 以 Redis Hash 存储 token→userId 映射并自动 TTL 续期；密码 BCrypt 哈希；UserContextHolder（ThreadLocal）全链路携带用户身份，多租户数据通过 user_id term filter 强制隔离。

- **对话与嵌入模型独立路由：** 自定义 FishLlmEnvironmentPostProcessor（EnvironmentPostProcessor + spring.factories）在 Spring Boot 早期阶段写入 spring.ai.model.chat，解决 DashScope / Ollama 双 Starter 共存时 Bean 歧义问题；Chat 与 Embedding 可独立选型（如 Chat 用本地 Ollama 降成本、Embedding 用 DashScope 保质量）。

- **全栈 SSE 流式协议：** 后端 SseEmitter（5 分钟超时）订阅 Flux<NodeOutput>，实现段聚合、全文重复、末尾长段重复三级去重后推送 SSE；约定 session / chunk / tool / done / error 五类事件，工具调用过程前端可视化呈现；前端 ReadableStream 逐 chunk 增量追加 + marked 实时渲染 Markdown，AbortController 支持用户中途取消，后端感知 IOException 后终止 Agent 推理。

- **Python Worker 高可用设计：** 消费循环实现三级优先级拉取——① 优先重试自身 PEL 中未 ACK 消息（断点续传）、② XAUTOCLAIM 认领其他消费者超 120s 未 ACK 的空闲消息（进程崩溃自动接管）、③ XREADGROUP 拉取新消息（正常消费）；finally 块保证无论成功/失败/非法消息均执行 XACK，杜绝毒消息在 PEL 中死循环；threading.local() 每线程独立复用 MySQL 连接并自动重连；pydantic-settings 配置与 Java application.yml 环境变量命名完全对齐，一份 .env 两端共用。
