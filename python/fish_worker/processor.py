# 单条文档的处理流水线 — 编排所有步骤
#
# 流程图（每条消息执行一次）：
#   MinIO 下载 → Parser 解析 → Chunker 分块 → Embedder 嵌入 → ES 批量写入 → MySQL 更新状态
#
# 异常分支：
#   UnsupportedFileTypeError → FAILED（不支持的 MIME 类型）
#   其他 Exception            → FAILED + re-raise（让 consumer 记录日志后 XACK）
#   解析结果为空              → SUCCESS + chunk_count=0（纯图片扫描件等）
#
# 关键设计：
#   tmp_root 传给 parser，避免重复创建临时目录
#   finally 中 shutil.rmtree 清理临时文件（类似 Java finally 关闭 FileInputStream）
"""Single-task ingest pipeline: MinIO → parse → chunk → embed → ES → MySQL."""

from __future__ import annotations

import logging
import os
import shutil
import tempfile

from fish_worker.chunker.text_chunker import chunk_elements
from fish_worker.deps import WorkerContext
from fish_worker.exceptions import UnsupportedFileTypeError
from fish_worker.parser.factory import ParserFactory

log = logging.getLogger(__name__)


class IngestProcessor:

    def __init__(self, ctx: WorkerContext) -> None:
        self._settings = ctx.settings
        self._minio = ctx.minio
        self._db = ctx.db
        self._es = ctx.es
        self._embedder = ctx.embedder

    def process(self, task: "IngestTask") -> None:
        """执行单条文档的完整处理流水线。"""
        # 创建临时工作目录（Linux: /tmp/fish-worker/{task_id}, Windows: %TEMP%/fish-worker/{task_id}）
        tmp_root = os.path.join(tempfile.gettempdir(), "fish-worker", task.task_id)
        os.makedirs(tmp_root, exist_ok=True)

        # 先标记 PROCESSING，防止其他 worker 通过 XAUTOCLAIM 重复认领
        self._db.update_status(task.task_id, "PROCESSING")

        try:
            # ---- 步骤 1: 从 MinIO 下载原文件 ----
            # get_object 返回 (bytes, content_type)，content_type 来自上传时 Java 侧设置的 HTTP 头
            content, content_type = self._minio.get_object(task.minio_path)

            # ---- 步骤 2: 按 MIME 类型选择解析器 ----
            parser = ParserFactory.get(content_type, task.file_name)
            elements = parser.parse(content, task.file_name or "upload.pdf", tmp_dir=tmp_root)

            # ---- 步骤 3: 空结果处理（如纯图片扫描件，unstructured fast 模式无法提取文字）----
            if not elements:
                self._db.update_status(
                    task.task_id,
                    "SUCCESS",
                    error_msg="no extractable text",
                    chunk_count=0,
                )
                log.warning("task_id=%s no extractable text", task.task_id)
                return

            # ---- 步骤 4: tiktoken 分块（512 token / 50 overlap）----
            chunks = chunk_elements(
                elements,
                chunk_size=self._settings.fish_worker_chunk_size,
                overlap=self._settings.fish_worker_chunk_overlap,
            )
            if not chunks:
                self._db.update_status(
                    task.task_id,
                    "SUCCESS",
                    error_msg="no extractable text after chunking",
                    chunk_count=0,
                )
                return

            # ---- 步骤 5: 批量调用 embedding API ----
            vectors = self._embedder.embed_batch([c.text for c in chunks])

            # ---- 步骤 6: 写入 ES ----
            # scope_type=PRIVATE → fish-user-knowledge（个人文档）；PUBLIC → fish-public-knowledge（组织知识）
            scope_private = task.scope_type.upper() == "PRIVATE"
            index_name = (
                self._settings.knowledge_user_index if scope_private else self._settings.knowledge_public_index
            )

            # 幂等：须在 index_name 确定后调用；同一 task_id 重处理前先清空该 doc_id 下旧切片
            self._es.delete_by_doc_id(index_name, task.task_id)

            self._es.bulk_index_document_chunks(
                index_name=index_name,
                task_id=task.task_id,
                scope_private=scope_private,
                user_id=task.user_id if scope_private else None,
                file_name=task.file_name or "",
                chunks=chunks,
                vectors=vectors,
                batch_size=self._settings.fish_worker_es_batch_size,
            )

            # ---- 步骤 7: 更新成功 ----
            self._db.update_status(task.task_id, "SUCCESS", chunk_count=len(chunks))
            log.info("task_id=%s SUCCESS chunks=%s", task.task_id, len(chunks))

        except UnsupportedFileTypeError as e:
            # 不支持的 MIME 类型 → FAILED，不 re-raise（不是系统错误）
            msg = str(e)[:500]
            self._db.update_status(task.task_id, "FAILED", error_msg=msg)
            log.warning("task_id=%s FAILED unsupported: %s", task.task_id, msg)

        except Exception as e:
            # 其他所有异常 → FAILED + re-raise
            # re-raise 让 consumer 感知到失败并记录日志
            # 但 consumer 的 finally 仍会 XACK（避免毒消息死循环）
            msg = repr(e)[:500]
            self._db.update_status(task.task_id, "FAILED", error_msg=msg)
            log.exception("task_id=%s FAILED", task.task_id)
            raise

        finally:
            # 清理临时目录 —— 类似 Java 的 try-finally 关闭资源
            shutil.rmtree(tmp_root, ignore_errors=True)


# ----------- 任务数据类 -----------

class IngestTask:
    """从 Redis Stream 消息解析出的单条文档任务。

    __slots__ 是 Python 内存优化手段：
        声明实例只允许有这些属性，禁止动态添加属性
        类比 Java 的 final class + 固定字段（但更强：连 __dict__ 都不会创建）
    """
    __slots__ = ("task_id", "minio_path", "scope_type", "user_id", "file_name", "file_size")

    def __init__(
        self,
        *,
        task_id: str,
        minio_path: str,
        scope_type: str,
        user_id: str,
        file_name: str,
        file_size: str | None = None,
    ) -> None:
        self.task_id = task_id
        self.minio_path = minio_path
        self.scope_type = scope_type
        self.user_id = user_id
        self.file_name = file_name
        self.file_size = file_size
