# 知识库管线 P0 修复方案（交付 Codex 实现）

> 范围：仅改 **Python Worker**（`python/fish_worker/**`），不改 Java 侧逻辑。
> 目标：修复两个 P0 缺陷，按阶段拆分，每阶段可独立提交与验证。

---

## 背景：两个 P0 缺陷

### P0-1 长任务被孤儿补偿误杀（状态/数据错乱）
- `processor.py` 只在开始时写一次 `update_status(PROCESSING)`，处理过程中**不刷新 `updated_at`**。
- Java `OrphanTaskCompensationService` 按 `status=PROCESSING AND updated_at < now - timeoutMinutes`（默认 10min）判孤儿，删 ES + 标 FAILED。
- `parser/pdf.py` 单线程逐页 OCR（每页 1–3s），几百页 PDF 轻松 >10min。
- 后果：Worker 仍在跑却被判孤儿 → ES 被删、状态被改 FAILED；Worker 跑完又写 SUCCESS，MySQL 与 ES 不一致，RAG 永久召回不到该文档。

### P0-3 embedding 任一批失败 → 整任务报废且无重试
- `embedder.py` 中 `r.raise_for_status()` 直接抛异常 → `processor.py` 整任务 FAILED。
- 大文档分多个 batch，任一遇到 DashScope 限流(429)/超时即全任务作废，且无退避重试，只能手动重传。

---

## 复查结论（实现时务必遵循）

1. **所有 SUCCESS 写入都要 CAS**：除最终成功外，`processor.py` 中"空结果""空分块"的提前 `return` 也是 OCR 之后的终态写入，同样加 `expected_status='PROCESSING'`。
2. **最终 SUCCESS 的 CAS 落空时清理 ES**：仅最终成功路径（已执行 bulk 写入）在 `rowcount==0` 时需 `delete_by_doc_id` 清掉本次写入的切片；提前 `return` 路径尚未写 ES，无需清理。
3. **FAILED 写入不加 CAS**：终态→终态重写幂等无害，保持现状。
4. **httpx 异常层级**：`raise_for_status()` 抛的 `httpx.HTTPStatusError` 不是 `httpx.RequestError` 的子类，会按预期"不可重试、立即抛出"；重试只命中 429/5xx 与 `httpx.RequestError`（连接/超时）。
5. **PyMySQL**：`cursor.execute()` 返回受影响行数；沿用现有 `with self._conn() as conn:` + 显式 `conn.commit()` 复用连接的写法，不改连接生命周期模型。
6. **不引入新依赖**：重试手写实现，不使用 tenacity。
7. **心跳约束**：心跳间隔须远小于补偿超时（默认 10min）；默认 30s 留足余量。Python 侧读不到 Java 的 `compensation.timeout-minutes`，仅在 `.env.example`/README 注明约束。

---

## 阶段拆分

- **阶段一**：P0-1 处理心跳 + 终态 CAS + ES 兜底清理
- **阶段二**：P0-3 embedding 指数退避重试
- **阶段三**：配置补全（`.env.example`）、文档更新、联调验证

三个阶段相互独立，建议分 3 个提交。

---

## 阶段一：处理心跳 + 终态 CAS（修复 P0-1）

### 目标
健康 Worker 的任务全程保持 `updated_at` 新鲜，补偿永不误触；Worker 真死后心跳停止，补偿仍正确兜底。极端竞态下不产生幽灵切片。

### 涉及文件
- `python/fish_worker/config.py`
- `python/fish_worker/db/mysql.py`
- `python/fish_worker/processor.py`

### 改动 1：`config.py` 新增心跳间隔
```python
fish_worker_heartbeat_seconds: int = Field(
    default=30, validation_alias="FISH_WORKER_HEARTBEAT_SECONDS"
)
```

### 改动 2：`db/mysql.py` 新增 touch / 连接清理，并为 update_status 增加 CAS
- 新增 `touch(task_id) -> int`：
```python
def touch(self, task_id: str) -> int:
    """心跳：仅当任务仍为 PROCESSING 时刷新 updated_at，返回受影响行数。"""
    sql = ("UPDATE document_metadata SET updated_at=NOW() "
           "WHERE task_id=%s AND status='PROCESSING'")
    with self._conn() as conn:
        with conn.cursor() as cur:
            n = cur.execute(sql, (task_id,))
        conn.commit()
    return n
```
- 新增 `close_current_thread_conn()`：
```python
def close_current_thread_conn(self) -> None:
    """关闭当前线程连接（心跳线程退出时调用，避免连接泄漏）。"""
    conn = getattr(self._local, "conn", None)
    if conn is not None:
        try:
            conn.close()
        finally:
            self._local.conn = None
```
- `update_status` 增加可选 `expected_status` 参数并返回 rowcount：
```python
def update_status(self, task_id, status, *, error_msg=None,
                  chunk_count=None, expected_status: str | None = None) -> int:
    sets = ["status=%s", "updated_at=NOW()"]
    params = [status]
    if error_msg is not None:
        sets.append("error_msg=%s")
        params.append(error_msg[:500] if error_msg else None)
    if chunk_count is not None:
        sets.append("chunk_count=%s")
        params.append(chunk_count)
    where = "WHERE task_id=%s"
    params.append(task_id)
    if expected_status is not None:
        where += " AND status=%s"
        params.append(expected_status)
    sql = f"UPDATE document_metadata SET {', '.join(sets)} {where}"
    with self._conn() as conn:
        with conn.cursor() as cur:
            n = cur.execute(sql, params)
        conn.commit()
    log.debug("document_metadata updated task_id=%s status=%s rows=%s", task_id, status, n)
    return n
```
> 兼容性：`expected_status` 默认 None，现有所有调用行为不变。

### 改动 3：`processor.py` 包裹心跳线程 + 终态 CAS
- 起始（`update_status(PROCESSING)` 之后）启动守护心跳线程：
```python
import threading
...
self._db.update_status(task.task_id, "PROCESSING")
stop = threading.Event()
hb = threading.Thread(target=self._heartbeat_loop, args=(task.task_id, stop), daemon=True)
hb.start()
try:
    ... # 现有步骤 1~6
finally:
    stop.set()
    hb.join(timeout=5)
    shutil.rmtree(tmp_root, ignore_errors=True)
```
- 新增心跳循环：
```python
def _heartbeat_loop(self, task_id, stop):
    interval = max(5, self._settings.fish_worker_heartbeat_seconds)
    try:
        while not stop.wait(interval):
            try:
                if self._db.touch(task_id) == 0:
                    break  # 已非 PROCESSING（被补偿/外部改动），停止心跳
            except Exception:
                log.warning("task_id=%s 心跳刷新失败（忽略，下次重试）", task_id)
    finally:
        self._db.close_current_thread_conn()
```
- 三处 SUCCESS 写入全部加 `expected_status="PROCESSING"`：
  - 步骤3 空结果 `update_status(SUCCESS, error_msg="no extractable text", chunk_count=0, expected_status="PROCESSING")`
  - 步骤4 空分块 `update_status(SUCCESS, error_msg="no extractable text after chunking", chunk_count=0, expected_status="PROCESSING")`
  - 步骤7 最终成功，并在 CAS 落空时清理 ES：
```python
updated = self._db.update_status(
    task.task_id, "SUCCESS", chunk_count=len(chunks), expected_status="PROCESSING")
if updated == 0:
    log.warning("task_id=%s 终态写入被跳过（疑似已被补偿标记 FAILED），清理本次 ES 切片", task.task_id)
    try:
        self._es.delete_by_doc_id(index_name, task.task_id)
    except Exception:
        log.warning("task_id=%s CAS 落空后清理 ES 失败", task.task_id)
else:
    log.info("task_id=%s SUCCESS chunks=%s", task.task_id, len(chunks))
```
> FAILED 写入（两个 except 分支）保持不变，不加 CAS。

### 验收标准
- 健康 Worker 处理 >10min 的任务全程保持 PROCESSING，最终 SUCCESS，补偿无误删（日志无该 task 的补偿记录）。
- Worker 中途被 kill：心跳停止，`updated_at` 转旧，超时后补偿正确标 FAILED。
- 构造"补偿先于 Worker 完成"竞态：Worker 终态 CAS `rowcount=0`，不回写 SUCCESS，并清理本次 ES 切片；MySQL 终值为 FAILED 且 ES 无残留。
- 心跳线程退出后无残留 MySQL 连接泄漏（多任务串行处理后连接数稳定）。

---

## 阶段二：embedding 指数退避重试（修复 P0-3）

### 目标
embedding 调用对瞬时错误（429/5xx/网络超时）有界退避重试，重试耗尽才失败；不可重试错误（其余 4xx）立即失败。

### 涉及文件
- `python/fish_worker/config.py`
- `python/fish_worker/chunker/embedder.py`

### 改动 1：`config.py` 新增重试参数
```python
fish_worker_embed_max_retries: int = Field(
    default=3, validation_alias="FISH_WORKER_EMBED_MAX_RETRIES")
fish_worker_embed_backoff_base: float = Field(
    default=1.0, validation_alias="FISH_WORKER_EMBED_BACKOFF_BASE")   # 秒
fish_worker_embed_backoff_max: float = Field(
    default=30.0, validation_alias="FISH_WORKER_EMBED_BACKOFF_MAX")   # 秒
```

### 改动 2：`embedder.py` 增加重试包装
- 内部可重试异常与状态集合：
```python
import random, time

_RETRYABLE_STATUS = {429, 500, 502, 503, 504}

class _Retryable(Exception):
    def __init__(self, status_code, retry_after=None):
        super().__init__(f"retryable status {status_code}")
        self.status_code = status_code
        self.retry_after = retry_after
```
- 通用重试 POST：
```python
def _post_with_retry(self, client, url, *, headers=None, json=None):
    max_retries = self._s.fish_worker_embed_max_retries
    base = self._s.fish_worker_embed_backoff_base
    cap = self._s.fish_worker_embed_backoff_max
    attempt = 0
    while True:
        try:
            r = client.post(url, headers=headers, json=json)
            if r.status_code in _RETRYABLE_STATUS:
                raise _Retryable(r.status_code, r.headers.get("Retry-After"))
            r.raise_for_status()   # 其余 4xx → HTTPStatusError，不可重试，直接抛
            return r
        except (httpx.RequestError, _Retryable) as e:
            attempt += 1
            if attempt > max_retries:
                raise
            delay = self._compute_delay(e, attempt, base, cap)
            log.warning("embedding 调用失败(第%s次重试) 等待%.1fs: %r", attempt, delay, e)
            time.sleep(delay)

@staticmethod
def _compute_delay(e, attempt, base, cap):
    retry_after = getattr(e, "retry_after", None)
    if retry_after:
        try:
            return min(float(retry_after), cap)
        except (TypeError, ValueError):
            pass
    return min(cap, base * (2 ** (attempt - 1))) + random.uniform(0, 0.5)
```
- 将 `_dashscope_embed` 每个 batch 的 `client.post(...)` + `raise_for_status()` 替换为 `r = self._post_with_retry(client, url, headers=..., json=body)`；解析逻辑 `_parse_dashscope_output` 不变。
- 将 `_ollama_embed` 每条 `client.post(...)` + `raise_for_status()` 替换为 `r = self._post_with_retry(client, f"{base}/api/embeddings", json={...})`；其余不变。

### 可选增强（本阶段不强制，可留 TODO）
- DashScope 偶发以 HTTP 200 + body `code` 返回限流时，将其识别为可重试。
- batch 之间加 `throttle_ms` 限速。
- 重试日志透传 `task_id`（需把 task_id 传入 embedder，改动略大，后置）。

### 验收标准
- mock embedding 先返回一次 429 再 200：任务重试后 SUCCESS。
- 持续 429 超过 `max_retries`：按预期 FAILED（且此时配合阶段一心跳，长重试期间任务不被误杀）。
- 返回 400/401：立即 FAILED，无重试。
- 网络超时（`httpx.RequestError`）：触发退避重试。

---

## 阶段三：配置补全 + 文档 + 联调验证

### 涉及文件
- `python/.env.example`
- `python/README.md`（如有 Worker 调优参数说明章节）
- 文档（可选）：`document/模块要点/模块5-知识库闭环与可靠性.md` 补充心跳与重试说明

### 改动
- `.env.example` 增加并注释以下变量及默认值：
  - `FISH_WORKER_HEARTBEAT_SECONDS=30`（须远小于 Java `fish.knowledge.compensation.timeout-minutes`，默认 10min）
  - `FISH_WORKER_EMBED_MAX_RETRIES=3`
  - `FISH_WORKER_EMBED_BACKOFF_BASE=1.0`
  - `FISH_WORKER_EMBED_BACKOFF_MAX=30.0`
- README 补充：心跳机制目的（避免长任务被孤儿补偿误杀）与重试机制目的（embedding 瞬时错误容错），以及心跳/补偿超时的约束关系。

### 联调验证（端到端）
1. 本地将 Java 补偿超时临时设为 2min、心跳设 10s，上传一份 >2min 的大 PDF（或临时在 parser 注入 sleep），确认：全程 PROCESSING → SUCCESS，补偿无误删。
2. kill Worker，确认 2min 后补偿正确标 FAILED。
3. 用本地代理/mock 对 embedding 注入一次 429，确认任务自动重试成功。
4. 跑通现有 Worker 相关测试（若有），确保无回归。

---

## 风险与回滚
- 改动集中在 Worker，单进程可独立回滚（回退提交即可），不影响 Java 对话/上传链路。
- 新增配置均有默认值，未配置环境变量时行为安全（心跳 30s、重试 3 次）。
- CAS 与 ES 兜底清理为防御性逻辑，正常路径（无补偿介入）行为与现状一致。

---

## 实现记录（Codex 2026-06-01）

### 已完成
- `python/fish_worker/config.py` 新增 Worker 心跳与 embedding 重试配置，默认值与计划一致：
  - `FISH_WORKER_HEARTBEAT_SECONDS=30`
  - `FISH_WORKER_EMBED_MAX_RETRIES=3`
  - `FISH_WORKER_EMBED_BACKOFF_BASE=1.0`
  - `FISH_WORKER_EMBED_BACKOFF_MAX=30.0`
- `python/fish_worker/db/mysql.py`：
  - `update_status` 增加 `expected_status` CAS 条件，并返回受影响行数。
  - 新增 `touch(task_id)`，仅刷新 `PROCESSING` 行的 `updated_at`。
  - 新增 `close_current_thread_conn()`，供心跳线程结束时主动释放线程本地连接。
- `python/fish_worker/processor.py`：
  - 每个任务进入 `PROCESSING` 后启动心跳线程，处理结束后停止。
  - 所有 `SUCCESS` 终态写入改为 `expected_status="PROCESSING"`。
  - ES bulk 成功后若 `SUCCESS` CAS 失败，会删除本轮 `doc_id` 已写入分片，避免 MySQL 终态与 ES 脏数据不一致。
- `python/fish_worker/chunker/embedder.py`：
  - DashScope 与 Ollama HTTP POST 统一走 `_post_with_retry`。
  - 429、500、502、503、504 与 `httpx.RequestError` 触发指数退避重试。
  - 400/401/403 等非瞬时错误仍立即失败。
- `python/.env.example` 与 `python/README.md` 已补充新配置和行为说明。
- 新增 `python/tests` 下 3 组 `unittest`：
  - embedding 429/网络错误重试、400 不重试。
  - MySQL CAS / touch / 线程本地连接关闭。
  - `SUCCESS` CAS 失败后的 ES 清理。

### 与计划的偏差 / 后置项
- 未引入 `tenacity` 等第三方重试库，当前用本地私有方法实现；这样不新增依赖，行为也更容易被单元测试覆盖。
- DashScope “HTTP 200 但 body.code 表示限流”的可重试识别仍按计划留作后置增强；当前保持 `_parse_dashscope_output` 原有语义，遇到 body error 会失败并标记任务 `FAILED`。
- 心跳失败不会立即中断主处理流程；原因是短暂 MySQL 抖动不应杀死 OCR / embedding，最终一致性由终态 CAS 与 ES 清理兜底。
- 未修改 `document/模块要点` 文档；本轮先按计划完成 Worker 代码、配置样例和 README。若需要正式架构文档同步，可在下一轮补充模块文档。

### 已验证
- `python/.venv/Scripts/python.exe -m unittest discover -s tests`
  - 结果：`Ran 7 tests ... OK`
- `python/.venv/Scripts/python.exe -m compileall fish_worker tests`
  - 结果：无语法错误。
