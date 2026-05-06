# MySQL 状态更新 — document_metadata 表
#
# 每个 Worker 线程复用自己的数据库连接（threading.local）
# 避免每次 update_status 都重新建立 TCP + MySQL 握手
"""MySQL updates for document_metadata (status / chunk_count / error_msg)."""

from __future__ import annotations

import logging
import threading
from typing import Any

import pymysql

from fish_worker.config import Settings

log = logging.getLogger(__name__)


class DocumentMetadataRepository:

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        # threading.local() 为每个线程创建独立的存储空间
        # 类比 Java 的 ThreadLocal<Connection>
        # 这样 ThreadPoolExecutor 中的每个线程都有自己的 MySQL 连接，不会相互干扰
        self._local = threading.local()

    def _conn(self) -> pymysql.Connection:
        """获取当前线程的数据库连接，断线自动重连。"""
        # getattr(obj, 'attr', default) 等价于 Java 的 obj.getAttr() 带默认值
        conn = getattr(self._local, "conn", None)
        if conn is None:
            conn = pymysql.connect(**self._settings.mysql_connect_kwargs)
            self._local.conn = conn
        else:
            try:
                conn.ping(reconnect=True)  # 先 ping 检测连接是否存活
            except Exception:
                # ping 失败 → 重建连接（原连接可能已被 MySQL 服务端断开）
                conn = pymysql.connect(**self._settings.mysql_connect_kwargs)
                self._local.conn = conn
        return conn

    def update_status(
        self,
        task_id: str,
        status: str,
        *,
        error_msg: str | None = None,
        chunk_count: int | None = None,
    ) -> None:
        """
        更新 document_metadata 的状态字段。

        Args:
            task_id:     document_metadata.task_id（关联键）
            status:      PENDING → PROCESSING → SUCCESS / FAILED
            error_msg:   错误/警告信息（截断至 500 字符）
            chunk_count: 成功写入 ES 的分片数量（仅 SUCCESS 时写入）
        """
        # 动态构建 SET 子句（只 UPDATE 传入的字段）
        sets: list[str] = ["status=%s", "updated_at=NOW()"]
        params: list[Any] = [status]

        if error_msg is not None:
            sets.append("error_msg=%s")
            params.append(error_msg[:500] if error_msg else None)

        if chunk_count is not None:
            sets.append("chunk_count=%s")
            params.append(chunk_count)

        params.append(task_id)

        # f-string 拼接 SET 子句（参数值通过 %s 占位符防 SQL 注入）
        sql = f"UPDATE document_metadata SET {', '.join(sets)} WHERE task_id=%s"

        # with conn as conn: 类似 Java try-with-resources，退出时自动 conn.close()
        # 但这里我们用 _conn() 复用连接，所以不 close（由线程生命周期管理）
        with self._conn() as conn:
            with conn.cursor() as cur:  # cursor 类似 JDBC Statement
                cur.execute(sql, params)
            conn.commit()  # 显式提交（PyMySQL 默认 autocommit=False）
        log.debug("document_metadata updated task_id=%s status=%s", task_id, status)
