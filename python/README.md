# Fish-Agent Python Worker (v2.2)

异步消费 Redis Stream（默认 `fish:doc:ingest`），从 RustFS `fish-docs` 桶下载原始文件，使用 **PyMuPDF + Tesseract OCR** 解析 PDF（渲染为图片后识别，无论字体编码如何都能正确提取中文），按 token 分块、嵌入后写入 **Elasticsearch**（用户文档 `fish-user-knowledge` / 公有 `fish-public-knowledge`；与 Java 侧 `fish-user-memory` 对话事实索引分离），并更新 MySQL `document_metadata`。

与 Java 上传链路（v2.1）解耦：仅依赖 Redis / MySQL / MinIO / ES 及一致的环境变量命名。

## 前置条件

- JDK 侧已完成上传并入队（`document_metadata.status=PENDING`）。
- **Redis / MySQL / MinIO / ES 四个中间件必须可访问**（本地或远程均可，通过 .env 配置）。
- **Tesseract OCR 引擎 + 简体中文语言包**（Windows 本地开发需手动安装，Docker 镜像已内置）。
- MySQL 表含 **`chunk_count`** 列：新建库使用仓库内 [`database/sql/fish_agent.sql`](../database/sql/fish_agent.sql)；已有库执行：
  ```sql
  ALTER TABLE document_metadata ADD COLUMN chunk_count INT NULL COMMENT 'Python Worker 成功写入 ES 的切片数量' AFTER error_msg;
  ```
- ES `dense_vector` 维度与嵌入模型一致（默认 **1536**，与 `DASHSCOPE_EMBEDDING_DIMENSIONS` 对齐）。
- Ollama 模式下须选用维度与索引一致的 embedding 模型，否则会写入警告或维度不匹配错误。

## 启动方式

有两种方式，根据你的目的选择：

| 方式 | 适用场景 | 改代码后 |
|------|----------|----------|
| **venv 直跑** | 本地开发、调试、改代码快速验证 | 立即生效，无需重建 |
| **Docker** | 生产部署、交付、CI/CD | 需要 `docker build` 重建镜像 |

---

## 方式一：venv 本地开发（推荐日常使用）

改完 `.py` 文件后 Ctrl+C 停掉再启动即可，秒级反馈。

### 1. 创建虚拟环境（一次性）

```bash
cd python

# Windows (PowerShell)
python -m venv .venv
.venv\Scripts\activate

# macOS / Linux
python -m venv .venv
source .venv/bin/activate
```

> 虚拟环境 = 项目独立的 Python 依赖目录，不会污染系统 Python。类比 Java 的 Gradle/Maven 本地仓库，但隔离粒度更粗。

### 2. 安装 Tesseract OCR（一次性，仅 Windows）

Windows 需要手动安装 Tesseract，Linux/macOS 和 Docker 镜像均已有。

1. 下载安装器：https://github.com/UB-Mannheim/tesseract/releases
   选 `tesseract-ocr-w64-setup-5.x.x.exe`（64 位）
2. 安装时**勾选 Chinese (Simplified) 语言包**（chi_sim）
3. 记住安装路径（默认 `C:\Program Files\Tesseract-OCR`）

### 3. 安装 Python 依赖（首次 + requirements.txt 变更时）

```bash
pip install -r requirements.txt
```

### 4. 创建配置文件

```bash
cp .env.example .env
```

然后编辑 `.env`，填入你的真实连接信息。至少修改以下必填项：

| 变量 | 说明 |
|------|------|
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接（存放 Stream 消息） |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | MySQL 连接（更新 document_metadata 状态） |
| `RUSTFS_ENDPOINT` / `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY` | MinIO 连接（下载原始文件） |
| `ELASTICSEARCH_URIS` | ES 连接（写入分块数据） |
| `DASHSCOPE_API_KEY` | 阿里云灵积 API Key（Python 侧调用 embedding） |

> 如果你已有 Java 后端在本地跑着，直接复用 Java 的 `application.yml` 里对应的值即可——两侧命名是对齐的。

### 5. 启动 Worker

```bash
python -m fish_worker
```

预期输出：

```
2026-05-05 10:00:00 INFO [fish_worker.main] Fish Worker starting stream=fish:doc:ingest group=fish-doc-worker-group concurrency=2
2026-05-05 10:00:00 INFO [fish_worker.health] health server listening on 0.0.0.0:8091
```

### 6. 验证

```bash
# 检查四个中间件连通性
curl http://localhost:8091/health
# → {"redis":"ok","mysql":"ok","elasticsearch":"ok","minio":"ok","status":"ok"}
```

如果返回 `"status":"degraded"`，看具体哪个组件报错，检查 `.env` 配置。

### 7. 停止

按 `Ctrl+C`（向主进程发送 SIGINT），Worker 会优雅关闭：处理完当前批次中的正在执行任务后退出。

### 8. 日常开发循环

```
改代码 → Ctrl+C 停 Worker → python -m fish_worker → 验证 → 改代码 → ...
```

**不需要每次重新 pip install**，除非你改了 `requirements.txt`。

---

## 方式二：Docker 部署

用于生产环境或交付。镜像包含 Python 运行时 + 所有依赖 + Worker 代码。

### 1. 构建镜像

```bash
# 在仓库根目录执行（不是在 python/ 下）
cd D:\1_Backend\Fish-Agent
docker build -t fish-agent-worker ./python
```

构建时间：首次约 3-5 分钟（需下载基础镜像 + pip install unstructured[pdf]），后续改代码重建几秒（依赖层缓存命中）。

### 2. 启动容器

```bash
docker run --rm -p 8091:8091 --env-file ./python/.env fish-agent-worker
```

- `--rm`：容器退出后自动删除（不留垃圾容器）
- `-p 8091:8091`：暴露健康检查端口
- `--env-file`：运行时注入环境变量（.env 不需要打进镜像）

### 3. 验证

```bash
curl http://localhost:8091/health
```

Docker 内置 HEALTHCHECK 每 30 秒自动探测一次，`docker ps` 的 STATUS 列会显示 `(healthy)`。

### 4. 改代码后

```bash
docker build -t fish-agent-worker ./python   # 重建（通常几秒）
docker run --rm -p 8091:8091 --env-file ./python/.env fish-agent-worker
```

---

## 关键行为摘要

| 项 | 说明 |
|----|------|
| MIME | 从 MinIO `GetObject` 响应头读取 `Content-Type`；`application/octet-stream` 且文件名以 `.pdf` 结尾时按 PDF 推断 |
| PDF解析 | `PyMuPDF` 渲染为 300 DPI 图片 → `pytesseract` OCR (chi_sim+eng) 识别。完全绕过字体编码 |
| 分块 | `tiktoken` cl100k_base，默认 512 token / 50 overlap，按页分组不跨页合并 |
| 嵌入 | `FISH_LLM_EMBEDDING_PROVIDER=DASHSCOPE`（批量≤25）或 `OLLAMA`（逐条）；429 / 5xx / 网络异常会指数退避重试，400/401 等确定性错误立即失败 |
| ES bulk | 默认每批 20 条；任一批失败则任务 `FAILED`，已写入分片不回滚 |
| 空文本 | 解析结果为空 → `SUCCESS`，`chunk_count=0`，`error_msg` 记录警告 |
| Stream | `XREADGROUP` + `XAUTOCLAIM`（idle≥120s）+ 处理后 **`XACK`**（含失败任务，避免毒消息死循环） |
| 处理心跳 | 长任务处于 `PROCESSING` 时会按 `FISH_WORKER_HEARTBEAT_SECONDS` 刷新 `updated_at`，防止 Java 侧孤儿补偿误判 |
| 状态流转 | `PENDING → PROCESSING → SUCCESS/FAILED`。写 `SUCCESS` 使用状态 CAS；若终态写入失败，会清理本轮已写 ES 分片。无论成败都 XACK。 |

### 长任务保护与重试

- `FISH_WORKER_HEARTBEAT_SECONDS` 默认 30 秒，建议明显小于 Java 侧孤儿补偿超时时间（当前 Java 默认按 10 分钟级别处理），这样 OCR / embedding 较慢时也能持续证明任务仍在执行。
- `FISH_WORKER_EMBED_MAX_RETRIES`、`FISH_WORKER_EMBED_BACKOFF_BASE`、`FISH_WORKER_EMBED_BACKOFF_MAX` 控制 embedding HTTP 重试。仅重试 429、5xx 和网络异常；鉴权、参数、维度等确定性错误仍会快速失败并把任务标记为 `FAILED`。

---

## 与 Java 对齐的配置键

`REDIS_*`、`RUSTFS_*`、`ELASTICSEARCH_*`、`DB_*` / `DB_URL`、`FISH_DOC_INGEST_STREAM`、`MEMORY_USER_INDEX`（Java 对话记忆）、`KNOWLEDGE_USER_INDEX`（≈ `fish.knowledge.user-knowledge-index-name`）、`KNOWLEDGE_PUBLIC_INDEX`（≈ `fish.knowledge.public-index-name`）、`DASHSCOPE_*`、`FISH_LLM_EMBEDDING_PROVIDER`、`OLLAMA_*` 与 [`application.yml`](../src/main/resources/application.yml) 一致。

Worker 专属调优键包括：`FISH_WORKER_CONCURRENCY`、`FISH_WORKER_CHUNK_SIZE`、`FISH_WORKER_CHUNK_OVERLAP`、`FISH_WORKER_ES_BATCH_SIZE`、`FISH_WORKER_DASHSCOPE_EMBED_BATCH`、`FISH_WORKER_BLOCK_MS`、`FISH_WORKER_HEALTH_PORT`、`FISH_WORKER_HEARTBEAT_SECONDS`、`FISH_WORKER_EMBED_MAX_RETRIES`、`FISH_WORKER_EMBED_BACKOFF_BASE`、`FISH_WORKER_EMBED_BACKOFF_MAX`。

---

## 水平扩展

多实例部署时使用同一 **`FISH_WORKER_CONSUMER_GROUP`**（默认 `fish-doc-worker-group`）；Redis 会将消息分给不同 consumer。

```bash
# 启动第二个 Worker 实例（与第一个共享消费组，自动负载均衡）
docker run --rm -p 8092:8091 --env-file ./python/.env \
  -e FISH_WORKER_HEALTH_PORT=8092 \
  fish-agent-worker
```

---

## 常见问题

### Q: 启动后立刻报 `Redis connection refused`

检查 `.env` 中 `REDIS_HOST/PORT/PASSWORD` 是否正确，Redis 是否在运行：

```bash
redis-cli ping   # 应返回 PONG
```

### Q: Worker 启动了但没有消费任何消息

1. 确认 Java 侧已经上传了文件并入队（`document_metadata.status=PENDING`）
2. 确认 `FISH_DOC_INGEST_STREAM` 的值 Java 和 Python 一致（默认 `fish:doc:ingest`）
3. 检查 Redis 中 stream 是否有消息：`redis-cli XLEN fish:doc:ingest`

### Q: 解析中文 PDF 出现乱码或 (cid:xxxx)

当前已使用 **PyMuPDF + Tesseract OCR**，渲染为图片后识别，不依赖字体编码。
应不会再出现乱码。

如果仍然是乱码：
- **Windows**：检查 Tesseract 是否安装并勾选了 Chinese (Simplified) 语言包
- **Linux/Docker**：检查 `tesseract-ocr-chi-sim` 是否已安装
- 确认 PDF 本身不是扫描件 + 复杂排版（OCR 本身有误差率）

### Q: OCR 太慢

每页约 1-3 秒（300 DPI）。可在 `pdf.py` 中将 `dpi=300` 改为 `dpi=200`
（速度提升 ~2x，识别率略降但对清晰文档影响不大）。

### Q: DashScope embedding 报维度不匹配

检查 `DASHSCOPE_EMBEDDING_DIMENSIONS` 是否与 ES 索引的 `dense_vector.dims` 一致。默认 text-embedding-v2 输出 1536 维。

### Q: 如何调试单个文件

可以写一个简单的测试脚本直接调 processor：

```python
from fish_worker.config import load_settings
from fish_worker.processor import IngestTask, IngestProcessor
from fish_worker.deps import WorkerContext
# ... 组装 ctx，调用 processor.process(task)
```

或者直接看 Worker 的 stdout 日志：每条任务的处理结果（SUCCESS/FAILED + chunk 数）都有一行 INFO 日志。
