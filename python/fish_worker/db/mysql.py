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
        expected_status: str | None = None,
    ) -> int:
        """
        更新 document_metadata 的状态字段。

        Args:
            task_id:     document_metadata.task_id（关联键）
            status:      PENDING → PROCESSING → SUCCESS / FAILED
            error_msg:   错误/警告信息（截断至 500 字符）
            chunk_count: 成功写入 ES 的分片数量（仅 SUCCESS 时写入）
            expected_status: 可选 CAS 条件；传入时仅当当前状态匹配才更新

        Returns:
            受影响的行数。终态 SUCCESS 用它判断是否被 Java 侧孤儿补偿抢先改写。
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
        if expected_status is not None:
            sql += " AND status=%s"
            params.append(expected_status)

        # with conn as conn: 类似 Java try-with-resources，退出时自动 conn.close()
        # 但这里我们用 _conn() 复用连接，所以不 close（由线程生命周期管理）
        with self._conn() as conn:
            with conn.cursor() as cur:  # cursor 类似 JDBC Statement
                affected = cur.execute(sql, params)
            conn.commit()  # 显式提交（PyMySQL 默认 autocommit=False）
        log.debug(
            "document_metadata updated task_id=%s status=%s affected=%s",
            task_id,
            status,
            affected,
        )
        return affected

    def touch(self, task_id: str) -> int:
        """刷新 PROCESSING 任务的 updated_at，用于告诉 Java 侧孤儿补偿“任务仍活着”。

        仅在当前状态仍为 PROCESSING 时更新。若返回 0，说明该任务已被外部流程改成终态
        或被补偿逻辑接管，Worker 后续写 SUCCESS 时会依赖 CAS 做最后确认。
        """
        sql = "UPDATE document_metadata SET updated_at=NOW() WHERE task_id=%s AND status='PROCESSING'"
        with self._conn() as conn:
            with conn.cursor() as cur:
                affected = cur.execute(sql, [task_id])
            conn.commit()
        log.debug("document_metadata touched task_id=%s affected=%s", task_id, affected)
        return affected

    def close_current_thread_conn(self) -> None:
        """关闭当前线程缓存的 MySQL 连接。

        心跳线程是短生命周期后台线程，结束时主动关闭连接，避免线程退出后连接对象
        只能等待 GC 回收。
        """
        conn = getattr(self._local, "conn", None)
        if conn is None:
            return
        try:
            conn.close()
        finally:
            self._local.conn = None
