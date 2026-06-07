# Redis 常量 / Key 速查

本文档对应后端 Java + Python Worker 代码中实际使用 Redis 的 **key / Stream / Group 命名**（截至 v4.2）。

当前项目 Redis 用途：
- **登录会话** (token → UserContext JSON)
- **短期记忆** (摘要 + 消息窗口)
- **文档入库 Stream** (Java 投递, Python Worker 消费)
- **对话限流** (v2.3：令牌桶 + SSE 并发计数器)
- **会话互斥锁** (v2.4：同一会话同时只有一个 SSE 流)
- **切片聚类缓存** (v4.2：K-Means 分组结果 24h 缓存)

---

## 一、配置项与默认值

### 1.1 登录会话 (v2.0+)

| 配置键 | Java 绑定 | 默认值 | 说明 |
|--------|-----------|--------|------|
| `fish.auth.session-key-prefix` | `AuthProperties.sessionKeyPrefix` | `fish:session` | 会话 key 前缀 |
| `fish.auth.session-ttl-seconds` | `AuthProperties.sessionTtlSeconds` | `86400` | TTL (秒), `SETEX` 写入 + 拦截器续期 |

源码：`AuthProperties`、`RedisSessionManager`、`GlobalAuthInterceptor`

### 1.2 短期记忆

| 配置键 | Java 绑定 | 默认值 | 说明 |
|--------|-----------|--------|------|
| `fish.memory.redis-key-prefix` | `MemoryProperties.redisKeyPrefix` | `fish:memory` | 所有短期记忆 key 前缀 |
| `fish.memory.short-term-ttl-days` | `MemoryProperties.shortTermTtlDays` | `30` | ZSET 与 summary key 共用 TTL (天) |

源码：`MemoryProperties`、`RedisShortTermMemoryStore`

### 1.3 文档入库 Stream (v2.1+)

| 配置键 | Java 绑定 | Python 配置 | 默认值 | 说明 |
|--------|-----------|-------------|--------|------|
| `fish.knowledge.document-ingest-stream-key` | `KnowledgeProperties.documentIngestStreamKey` | `FISH_DOC_INGEST_STREAM` | `fish:doc:ingest` | Stream 键名 |
| — (仅 Python) | — | `FISH_WORKER_CONSUMER_GROUP` | `fish-doc-worker-group` | 消费者组名 |

源码：Java `KnowledgeProperties`、Python `config.py`、`consumer.py`

### 1.4 对话限流 (v2.3+)

| 配置键 | Java 绑定 | 默认值 | 说明 |
|--------|-----------|--------|------|
| `fish.rate-limit.enabled` | `RateLimitProperties.enabled` | `true` | 限流总开关 |
| `fish.rate-limit.token-bucket.capacity` | `TokenBucket.capacity` | `60` | 令牌桶容量（最大突发数） |
| `fish.rate-limit.token-bucket.refill-rate` | `TokenBucket.refillRate` | `1.0` | 补充速率（tokens/s，约 60/分钟） |
| `fish.rate-limit.token-bucket.key-ttl` | `TokenBucket.keyTtl` | `120` | 令牌 Hash TTL（秒） |
| `fish.rate-limit.sse-concurrent.max-connections` | `SseConcurrent.maxConnections` | `2` | 每用户最大并发 SSE 数 |
| `fish.rate-limit.sse-concurrent.key-ttl` | `SseConcurrent.keyTtl` | `300` | SSE 计数 TTL（秒，兜底防泄漏） |

源码：`RateLimitProperties`、`RateLimitService`、`RateLimitInterceptor`

---

## 二、Key 模式

### 2.1 登录会话 (按 token)

前缀 `fish:session`, `{token}` = 登录时下发的 UUID 去横线, 前端存 `localStorage` key `fish-agent-token`, 请求时放在 Header `X-Auth-Token`。

| 用途 | Key 模式 | 类型 | 代码位置 |
|------|----------|------|----------|
| 会话载荷 | `fish:session:{token}` | String (JSON) | `RedisSessionManager.sessionKey()` |

Value 为 `UserContext` 的 JSON 序列化: `{"userId":1,"username":"admin","nickname":"管理员","role":"ADMIN"}`.

**示例**: `fish:session:a1b2c3d4e5f6789012345678901234ab`

### 2.2 短期记忆 (按会话)

前缀 `fish:memory`, `{sessionId}` = 聊天会话 ID。

| 用途 | Key 模式 | 类型 | 代码位置 |
|------|----------|------|----------|
| 短期摘要文本 | `fish:memory:short:{sessionId}:summary` | String | `RedisShortTermMemoryStore.summaryKey()` |
| 近期消息窗口 | `fish:memory:short:{sessionId}:messages` | ZSET | `RedisShortTermMemoryStore.messagesKey()` |

两端写入时共用同一 TTL (`shortTermTtlDays`, 默认 30 天)。

**示例** (sessionId=`abc-123`):
- `fish:memory:short:abc-123:summary`
- `fish:memory:short:abc-123:messages`

### 2.3 文档入库 Stream (v2.1+)

| 用途 | Key / 名称 | 类型 | 代码位置 |
|------|-----------|------|----------|
| 文档解析任务队列 | `fish:doc:ingest` | Stream | Java `KnowledgeIngestionService.publishStream()` / Python `consumer.py` |
| 消费者组 | `fish-doc-worker-group` | Consumer Group | Python `consumer.py:ensure_group()` |

**Stream 消息字段** (Java `XADD` → Python `XREADGROUP`):

| 字段 | 类型 | 说明 |
|------|------|------|
| `task_id` | String | `document_metadata.task_id`, 关联键 |
| `minio_path` | String | RustFS `fish-docs` 桶中的对象路径 |
| `scope_type` | String | `PRIVATE` / `PUBLIC`, 决定写入哪个 ES 索引 |
| `user_id` | String | 上传用户 ID, Python Worker 写入 ES 时用 |
| `file_name` | String | 原始文件名 |
| `file_size` | String | 文件字节数 |

**消费语义**:
- 使用 `XREADGROUP` 消费者组模式, 支持多 Worker 水平扩展
- `XAUTOCLAIM` idle≥120s 自动认领崩溃 Worker 的未确认消息
- 处理完成 (包括失败) 后统一 `XACK`, 避免毒消息死循环
- 失败任务状态更新为 `FAILED` 后不再重试

### 2.4 对话限流 (v2.3+)

前缀 `fish:ratelimit`，按 `{userId}` 隔离。

| 用途 | Key 模式 | 类型 | 代码位置 |
|------|----------|------|----------|
| 令牌桶（refill + consume） | `fish:ratelimit:token:{userId}` | Hash | `RateLimitService.tokenKey()` |
| SSE 并发连接计数 | `fish:ratelimit:sse:{userId}` | String | `RateLimitService.sseKey()` |

**令牌桶 Hash 字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `tokens` | Float | 当前剩余令牌数 |
| `lastRefillTime` | Long | 上次补充时间戳（ms） |

**操作语义**：
- 令牌桶和 SSE 并发计数均通过 **Lua 脚本**在 Redis 侧原子执行，保证 refill + consume 不可分割
- 令牌桶：每次请求惰性计算 `elapsed × refillRate` 补充令牌，无后台定时器
- SSE 并发：`INCR` 后若超限则**脚本内回滚 `DECR`**；连接结束时由 `ChatService` 调用 `DECR` Lua（仅在计数 > 0 时递减）
- 两个 key 均有 TTL 兜底，正常路径通过回调主动清理
- Redis 执行异常时 **fail-open**（放行），避免限流故障拖垮对话

**示例** (userId=5)：
- `fish:ratelimit:token:5` → Hash `{"tokens": "42.5", "lastRefillTime": "1746500000000"}`
- `fish:ratelimit:sse:5` → String `"1"`（1 路活跃 SSE）

### 2.5 会话互斥锁 (v2.4+)

前缀 `fish:mutex`，按 `{userId}` + `{sessionId}` 隔离。

| 用途 | Key 模式 | 类型 | 代码位置 |
|------|----------|------|----------|
| 会话互斥锁 | `fish:mutex:session:{userId}:{sessionId}` | String (NX) | `RateLimitService.tryAcquireSessionLock()` |

**操作语义**：
- `SET NX EX 120` 获取锁（120s TTL 兜底）
- 正常路径：SSE 结束时 `DEL` 释放（`releaseSseSlotOnce` 合并释放）
- 异常路径：120s TTL 自动过期，防止死锁

### 2.6 切片聚类缓存 (v4.2+)

| 用途 | Key 模式 | 类型 | TTL | 代码位置 |
|------|----------|------|-----|----------|
| K-Means 聚类结果 | `fish:chunk:clusters:{taskId}` | String (JSON) | 24h | `ChunkClusterService` |

**操作语义**：
- 文档切片 K-Means 聚类 + LLM 标题摘要较重（数秒），结果缓存 24h
- 文档删除时清除对应缓存
- 缓存未命中时重新计算并写入

---

## 三、非 Key: 连接与库号

Spring Data Redis 连接参数见 `spring.data.redis.*`, 默认 `database=2`。

Python Worker 通过环境变量 `REDIS_HOST/PORT/PASSWORD/DATABASE` 连接同一 Redis 实例, 默认 `REDIS_DATABASE=2`。

---

## 四、完整示例速查

| 场景 | 示例值 |
|------|--------|
| 用户登录后 token 为 `a1b2...34ab` | `fish:session:a1b2c3d4e5f6789012345678901234ab` |
| 会话 `abc-123` 的短期摘要 | `fish:memory:short:abc-123:summary` |
| 会话 `abc-123` 的消息窗口 | `fish:memory:short:abc-123:messages` |
| 文档解析 Stream | `fish:doc:ingest` (Stream) / `fish-doc-worker-group` (Group) |
| 用户 5 的令牌桶 | `fish:ratelimit:token:5` (Hash) |
| 用户 5 的 SSE 并发计数 | `fish:ratelimit:sse:5` (String) |
| 用户 5 会话 abc-123 的互斥锁 | `fish:mutex:session:5:abc-123` (String NX, TTL 120s) |
| 文档 task-xyz 的切片聚类缓存 | `fish:chunk:clusters:task-xyz` (String JSON, TTL 24h) |

---

_文档版本：v4.2 · 更新日期：2026-06-07_
