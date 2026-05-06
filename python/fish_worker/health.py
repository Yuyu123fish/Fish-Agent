# 可选的 HTTP /health 端点 — Docker HEALTHCHECK + 运维监控用
#
# 探测四项基础设施连通性：Redis / MySQL / Elasticsearch / MinIO
# 全部 ok → 200 {"status":"ok"}
# 任一不可用 → 503 {"status":"degraded"}
#
# 运行方式：后台 daemon 线程（主线程退出时自动终止）
# 类比 Java：new Thread(() -> server.start()).setDaemon(true).start()
#
# TTL 缓存：每次 health check 会新建 4 个连接（Redis/MySQL/ES/MinIO），
# Docker HEALTHCHECK 每 30s 一次没问题，但如果有外部负载均衡器高频探测，
# 10s TTL 缓存可避免连接风暴。
"""Optional HTTP /health for Docker / ops (checks Redis, MySQL, ES, MinIO reachability)."""

from __future__ import annotations

import json
import logging
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Any

import pymysql
import redis
from elasticsearch import Elasticsearch

from fish_worker.config import Settings
from fish_worker.storage.minio import DocObjectStore

log = logging.getLogger(__name__)


def _check_redis(settings: Settings) -> str:
    """ping Redis，失败抛异常由上层 catch 转成 error 字符串。"""
    r = redis.Redis(
        host=settings.redis_host,
        port=settings.redis_port,
        password=settings.redis_password or None,
        db=settings.redis_database,
    )
    r.ping()
    return "ok"


def _check_mysql(settings: Settings) -> str:
    # **settings.mysql_connect_kwargs 是 Python 的字典解包语法
    # 等价于 Java 的 builder.host(x).port(y).database(z).build()
    conn = pymysql.connect(**settings.mysql_connect_kwargs)
    try:
        conn.ping()
        return "ok"
    finally:
        conn.close()


def _check_es(settings: Settings) -> str:
    kw: dict[str, Any] = {}
    if settings.elasticsearch_username:
        kw["basic_auth"] = (
            settings.elasticsearch_username,
            settings.elasticsearch_password or "",
        )
    es = Elasticsearch(settings.es_hosts, **kw)
    if not es.ping():
        raise RuntimeError("ping failed")
    return "ok"


def _check_minio(settings: Settings) -> str:
    if not DocObjectStore(settings).ping_bucket():
        raise RuntimeError(f"bucket {settings.rustfs_bucket_docs!r} missing")
    return "ok"


# ---- TTL 缓存：避免每次 /health 请求都新建四个连接 ----
_check_cache: dict[str, Any] = {}
_check_cache_time = 0.0
_CACHE_TTL = 10.0  # 秒


def start_health_server(settings: Settings) -> threading.Thread:
    """启动健康检查 HTTP 服务（后台 daemon 线程）。"""
    port = settings.fish_worker_health_port

    def build_checks() -> dict[str, Any]:
        global _check_cache, _check_cache_time
        now = time.time()
        # 缓存未过期，直接返回上次结果
        if now - _check_cache_time < _CACHE_TTL:
            return _check_cache

        body: dict[str, Any] = {}
        overall = "ok"
        # 逐个检查四个依赖，任意失败整体标记为 degraded
        for name, fn in (
            ("redis", lambda: _check_redis(settings)),
            ("mysql", lambda: _check_mysql(settings)),
            ("elasticsearch", lambda: _check_es(settings)),
            ("minio", lambda: _check_minio(settings)),
        ):
            try:
                body[name] = fn()
            except Exception as e:
                body[name] = f"error: {e}"
                overall = "degraded"
        body["status"] = overall
        _check_cache = body
        _check_cache_time = now
        return body

    # Python 的内置 HTTP 服务器 — 类比 Java 的 com.sun.net.httpserver.HttpServer
    # 功能简单但够用（health check 不需要高并发）
    class Handler(BaseHTTPRequestHandler):
        # 抑制默认的访问日志（否则每条 GET /health 都往 stdout 打一行）
        def log_message(self, fmt: str, *args: Any) -> None:
            log.debug(fmt, *args)

        # do_GET 响应 GET 请求（类似 HttpServlet 的 doGet）
        def do_GET(self) -> None:  # noqa: N802
            if self.path not in ("/health", "/health/"):
                self.send_response(404)
                self.end_headers()
                return

            payload = build_checks()
            raw = json.dumps(payload).encode("utf-8")
            code = 200 if payload.get("status") == "ok" else 503
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)  # wfile 是响应的 OutputStream

    def run() -> None:
        server = HTTPServer(("0.0.0.0", port), Handler)
        log.info("health server listening on 0.0.0.0:%s", port)
        server.serve_forever()  # 阻塞当前线程，直到 shutdown

    t = threading.Thread(target=run, name="health-http", daemon=True)
    t.start()
    return t
