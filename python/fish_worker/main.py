# CLI 入口 — 组装依赖、注册信号、启动消费循环
#
# 启动流程（类比 Spring Boot 的 main）：
#   1. load_settings()        → 读取环境变量，类似 @ConfigurationProperties
#   2. WorkerContext(...)     → 手工 DI，组装所有依赖
#   3. start_health_server()  → 后台线程启动 HTTP /health（Docker HEALTHCHECK 用）
#   4. signal.signal()        → 注册 SIGINT/SIGTERM 处理器（类似 Runtime.getRuntime().addShutdownHook）
#   5. consumer.run_forever() → 阻塞主线程，XREADGROUP 循环
"""CLI entry: Fish-Agent document ingest worker."""

from __future__ import annotations

import logging
import signal
import sys

from fish_worker.chunker.embedder import Embedder
from fish_worker.config import load_settings
from fish_worker.consumer import StreamConsumer
from fish_worker.db.mysql import DocumentMetadataRepository
from fish_worker.deps import WorkerContext
from fish_worker.health import start_health_server
from fish_worker.storage.elasticsearch import ElasticsearchIndexer
from fish_worker.storage.minio import DocObjectStore


def _setup_logging() -> None:
    # Python 的日志等价于 SLF4J + Logback：
    #   logging.basicConfig  = logback.xml 最小配置
    #   logging.getLogger()  = LoggerFactory.getLogger()
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
        stream=sys.stdout,
    )


def main() -> int:
    _setup_logging()
    log = logging.getLogger("fish_worker.main")
    settings = load_settings()

    # 手工 DI：把各个组件装配到 WorkerContext（相当于 Spring 的 ApplicationContext）
    ctx = WorkerContext(
        settings=settings,
        minio=DocObjectStore(settings),
        db=DocumentMetadataRepository(settings),
        es=ElasticsearchIndexer(settings),
        embedder=Embedder(settings),
    )

    # 启动 /health HTTP 端点（Docker HEALTHCHECK 指令会定期 GET）
    if settings.fish_worker_health_port > 0:
        start_health_server(settings)

    consumer = StreamConsumer(ctx)
    consumer.ensure_group()  # 首次启动自动创建消费者组（幂等）

    # 注册优雅关闭 —— 类比 JVM shutdown hook
    def _stop(*_: object) -> None:
        log.info("shutdown signal received")
        consumer.stop()

    signal.signal(signal.SIGINT, _stop)   # Ctrl+C
    signal.signal(signal.SIGTERM, _stop)  # docker stop 发的信号

    log.info(
        "Fish Worker starting stream=%s group=%s concurrency=%s",
        settings.fish_doc_ingest_stream,
        settings.fish_worker_consumer_group,
        settings.fish_worker_concurrency,
    )
    consumer.run_forever()  # 阻塞直到收到关闭信号
    log.info("Fish Worker stopped")
    return 0


if __name__ == "__main__":
    # raise SystemExit(0) 等价于 System.exit(0)
    raise SystemExit(main())
