# Redis Stream 消费者 — 核心消费循环
#
# 消费者组模式（类比 Kafka consumer group）：
#   - 同一 group 内的多个 worker 实例自动负载均衡（Redis 将消息轮询分发）
#   - XREADGROUP ">"  → 读取新消息（类似 Kafka poll 且未提交的）
#   - XREADGROUP "0"  → 重读当前 consumer 的 PEL 中未 ACK 消息（断点续传）
#   - XAUTOCLAIM       → 认领其他 consumer 超过 120s 未 ACK 的 idle 消息（崩溃恢复）
#   - XACK              → 确认处理完成，从 PEL 移除（类似 Kafka commit offset）
#
# 多线程模型：
#   ThreadPoolExecutor（类似 Java 同名类）提交任务 → _handle_one 处理单条消息
#   as_completed() 等待任意 future 完成后检查 running flag，实现优雅关闭
#
# 关键设计：
#   无论处理成功/失败，最终都会 XACK（避免毒消息死循环）
#   失败任务的状态已在 processor.py 中更新为 FAILED，XACK 只是不让它滞留 PEL
"""Redis Stream consumer: XREADGROUP, XAUTOCLAIM, XACK."""

from __future__ import annotations

import concurrent.futures
import logging
import os
import socket
import threading
from concurrent.futures import ThreadPoolExecutor
from typing import Any

import redis

from fish_worker.deps import WorkerContext
from fish_worker.processor import IngestProcessor, IngestTask

log = logging.getLogger(__name__)


def _decode_fields(raw: dict[Any, Any]) -> dict[str, str]:
    """Redis Stream 返回的 field-value 对可能是 bytes → 统一 decode 为 str。"""
    out: dict[str, str] = {}
    for k, v in raw.items():
        key = k.decode() if isinstance(k, bytes) else str(k)
        if v is None:
            out[key] = ""
        elif isinstance(v, bytes):
            out[key] = v.decode("utf-8", errors="replace")
        else:
            out[key] = str(v)
    return out


def _fields_to_task(fields: dict[str, str]) -> IngestTask:
    """把 Redis 消息的 field→value 映射转成强类型的 IngestTask。"""
    return IngestTask(
        task_id=fields.get("task_id", "").strip(),
        minio_path=fields.get("minio_path", "").strip(),
        scope_type=fields.get("scope_type", "PRIVATE").strip(),
        user_id=fields.get("user_id", "").strip(),
        file_name=fields.get("file_name", "").strip(),
        file_size=fields.get("file_size"),
    )


class StreamConsumer:

    def __init__(self, ctx: WorkerContext) -> None:
        self._s = ctx.settings
        self._processor = IngestProcessor(ctx)

        # Redis 客户端：decode_responses=False 表示保留 bytes 类型
        # （我们需要手动 decode，因为 Redis Stream 返回格式需要精确控制）
        self._redis = redis.Redis(
            host=self._s.redis_host,
            port=self._s.redis_port,
            password=self._s.redis_password or None,
            db=self._s.redis_database,
            decode_responses=False,
        )
        self._stream = self._s.fish_doc_ingest_stream
        self._group = self._s.fish_worker_consumer_group
        # Consumer 标识：主机名-PID，区分不同 worker 实例（Redis 用于 PEL 追踪）
        self._consumer = f"{socket.gethostname()}-{os.getpid()}"

        # threading.Event 是线程安全的布尔标志，类似 Java 的 CountDownLatch(1) 或 volatile boolean
        # set() = 设为 True, clear() = 设为 False, is_set() = 读取值
        self._running = threading.Event()
        self._running.set()

    def ensure_group(self) -> None:
        """创建消费者组（幂等：已存在则忽略 BUSYGROUP 错误）。"""
        try:
            # id="0" 表示创建 group 时从 stream 头部开始（仅首次创建生效）
            # mkstream=True 表示 stream 不存在时自动创建
            self._redis.xgroup_create(self._stream, self._group, id="0", mkstream=True)
            log.info("created redis stream group stream=%s group=%s", self._stream, self._group)
        except redis.ResponseError as e:
            # BUSYGROUP → Consumer Group name already exists（正常，忽略）
            if "BUSYGROUP" not in str(e).upper():
                raise

    def stop(self) -> None:
        """通知消费循环退出（由信号处理器在主线程调用）。"""
        self._running.clear()

    # ----------- 消息拉取（三级优先级）-----------

    def _xautoclaim_batch(self) -> list[tuple[str, dict[str, str]]]:
        """XAUTOCLAIM：认领其他 consumer 超过 120s 未 ACK 的 idle 消息（进程崩溃恢复）。"""
        try:
            # 120_000ms = 120s idle 阈值；"0-0" 表示从最早的消息开始认领
            out = self._redis.xautoclaim(
                self._stream,
                self._group,
                self._consumer,
                120_000,
                "0-0",
                count=10,
            )
        except redis.ResponseError:
            log.warning("xautoclaim failed stream=%s group=%s", self._stream, self._group, exc_info=True)
            return []
        # xautoclaim 返回 (claimed_ids_list, messages_list)
        if not out or len(out) < 2:
            return []
        messages = out[1]
        parsed: list[tuple[str, dict[str, str]]] = []
        for item in messages:
            if not item:
                continue
            msg_id, data = item[0], item[1]
            sid = msg_id.decode() if isinstance(msg_id, bytes) else str(msg_id)
            parsed.append((sid, _decode_fields(data)))
        return parsed

    def _read_new(self, block_ms: int, count: int = 10) -> list[tuple[str, dict[str, str]]]:
        """XREADGROUP ">"：读取新消息（阻塞 block_ms 毫秒等待）。"""
        block = block_ms if block_ms > 0 else None
        resp = self._redis.xreadgroup(
            self._group,
            self._consumer,
            {self._stream: ">"},  # ">" 表示只读未分发给当前 consumer 的新消息
            count,
            block,
        )
        return self._parse_xread(resp)

    def _read_pending_own(self, count: int = 10) -> list[tuple[str, dict[str, str]]]:
        """XREADGROUP "0"：重读当前 consumer 自己 PEL 中未 ACK 的消息（优先处理遗留任务）。"""
        resp = self._redis.xreadgroup(
            self._group,
            self._consumer,
            {self._stream: "0"},  # "0" 表示从 PEL 中读取（不是新消息）
            count,
            None,
        )
        return self._parse_xread(resp)

    @staticmethod
    def _parse_xread(resp: Any) -> list[tuple[str, dict[str, str]]]:
        """把 redis-py 的 xreadgroup 返回值统一转成 [(msg_id, {field: value})] 格式。"""
        if not resp:
            return []
        _stream_name, entries = resp[0]
        out: list[tuple[str, dict[str, str]]] = []
        for msg_id, data in entries:
            sid = msg_id.decode() if isinstance(msg_id, bytes) else str(msg_id)
            out.append((sid, _decode_fields(data)))
        return out

    def _collect_batch(self) -> list[tuple[str, dict[str, str]]]:
        """三级优先级拉取消息：
        1) 自己的 PEL 未 ACK 消息（优先，避免积压）
        2) XAUTOCLAIM 其他 consumer 的 idle 消息（崩溃恢复）
        3) 新消息 XREADGROUP ">"（正常消费）
        """
        batch = self._read_pending_own()
        if batch:
            return batch
        batch = self._xautoclaim_batch()
        if batch:
            return batch
        return self._read_new(self._s.fish_worker_block_ms)

    # ----------- 单条消息处理 -----------

    def _handle_one(self, msg_id: str, fields: dict[str, str]) -> None:
        """处理单条 Stream 消息：解析 → 处理 → XACK（finally 保证无论如何都 ACK）。"""
        try:
            task = _fields_to_task(fields)
            if not task.task_id or not task.minio_path:
                log.error("invalid stream payload msg_id=%s fields=%s", msg_id, fields)
                return  # finally 块仍会执行 XACK，避免垃圾消息滞留 PEL
            self._processor.process(task)
        except Exception:
            log.exception("process failed msg_id=%s", msg_id)
        finally:
            # 关键：无论成功/失败/非法消息，都 XACK
            # 失败任务已在 processor.py 中更新状态为 FAILED，不需要重试
            # 不 XACK 会导致消息永驻 PEL → XAUTOCLAIM 反复认领 → 死循环
            try:
                self._redis.xack(self._stream, self._group, msg_id)
            except redis.RedisError:
                log.exception("XACK failed msg_id=%s", msg_id)

    # ----------- 主循环 -----------

    def run_forever(self) -> None:
        """主消费循环：拉取消息 → 提交线程池处理 → 等待完成 → 检查关闭标志 → 循环。

        类比 Java：while(running) { messages = poll(); executor.invokeAll(tasks); }
        但这里用 submit + as_completed 替代 invokeAll，以便在关闭时能取消未完成的任务。
        """
        workers = max(1, self._s.fish_worker_concurrency)
        # with ThreadPoolExecutor 类似 Java 的 try-with-resources（退出时自动 shutdown）
        with ThreadPoolExecutor(max_workers=workers) as pool:
            while self._running.is_set():
                msgs = self._collect_batch()
                if not msgs:
                    continue  # 无消息，回到循环顶部检查 running flag

                # 批量提交任务到线程池
                futures = [pool.submit(self._handle_one, *p) for p in msgs]

                # as_completed 类似 Java 的 ExecutorCompletionService：
                # 哪个先完成就先返回哪个（不是按提交顺序）
                for f in concurrent.futures.as_completed(futures):
                    try:
                        f.result()  # 拿返回值（异常已在 _handle_one 内部 catch，不会到这里）
                    except Exception:
                        pass
                    if not self._running.is_set():
                        break  # 收到关闭信号，不再等剩余任务

                # 收到关闭信号：取消所有未开始的任务（正在执行的不受影响）
                if not self._running.is_set():
                    for f in futures:
                        f.cancel()
                    break
