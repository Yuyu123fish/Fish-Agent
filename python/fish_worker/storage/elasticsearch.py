# Elasticsearch 批量写入 — 文档分块索引
#
# 批量策略：
#   - 每批 20 条 bulk index（可配置）
#   - 某批失败 → 立即抛 RuntimeError，外层标记任务 FAILED
#   - 不回滚已写入批次（ES 无事务，已写入的分片就留在索引里）
#   - ES 文档 _id = {task_id}_{chunk_index}，重试时覆盖更新（天然幂等）
#
# 生成器模式 (gen_actions)：
#   yield 类似 Java 的 Stream.iterator() 或 return 一个 Iterable
#   用生成器惰性产出 action dict，避免一次性构建大列表
"""Bulk index document chunks into fish-user-knowledge (PRIVATE) or fish-public-knowledge (PUBLIC)."""

from __future__ import annotations

import logging
import time
from typing import Any, Iterable

from elasticsearch import Elasticsearch
from elasticsearch.helpers import bulk

from fish_worker.chunker.text_chunker import TextChunk
from fish_worker.config import Settings

log = logging.getLogger(__name__)


class ElasticsearchIndexer:

    def __init__(self, settings: Settings) -> None:
        self._s = settings
        kw: dict[str, Any] = {}
        if settings.elasticsearch_username:
            kw["basic_auth"] = (
                settings.elasticsearch_username,
                settings.elasticsearch_password or "",
            )
        # Elasticsearch() 连接是线程安全的（内部连接池）
        self._client = Elasticsearch(settings.es_hosts, **kw)

    def delete_by_doc_id(self, index_name: str, doc_id: str) -> None:
        """
        按业务字段 doc_id（与 document_metadata.task_id 一致）删除索引中该文档的全部 chunk。
        在 bulk 写入前调用，避免 Worker 重试时旧 chunk 数量不一致导致 ES 残留脏数据。
        """
        try:
            # elasticsearch-py 8.x：query 为顶层 DSL，refresh=True 便于紧随其后的 bulk 立即可见
            self._client.delete_by_query(
                index=index_name,
                query={"term": {"doc_id": doc_id}},
                refresh=True,
                conflicts="proceed",
            )
            log.debug("ES delete_by_query doc_id=%s index=%s", doc_id, index_name)
        except Exception as e:
            log.warning("ES delete_by_doc_id failed doc_id=%s index=%s: %s", doc_id, index_name, e)

    def bulk_index_document_chunks(
        self,
        *,
        index_name: str,
        task_id: str,
        scope_private: bool,
        user_id: str | None,
        file_name: str,
        file_type: str,
        chunks: list[TextChunk],
        vectors: list[list[float]],
        batch_size: int,
    ) -> None:
        """
        Args:
            index_name:       fish-user-knowledge（PRIVATE）或 fish-public-knowledge（PUBLIC）
            task_id:          document_metadata.task_id（关联键）
            scope_private:    True → PRIVATE（私有用户知识库）, False → PUBLIC（组织知识库）
            user_id:          仅 PRIVATE 时写入，用于 ES 查询时的权限过滤
            file_name:        原始文件名，PRIVATE/PUBLIC 均写入 doc_name 字段
            file_type:        文件类型（pdf/docx/xlsx/pptx/html/txt/md）
            chunks:           token 分块结果
            vectors:          对应 embedding 向量
            batch_size:       每批 bulk 条数
        """
        if len(chunks) != len(vectors):
            raise ValueError("chunks and vectors length mismatch")
        # 单次任务的所有分片使用同一 ms 时间戳（方便批量查询排序）
        now_ms = int(time.time() * 1000)

        # ---- 生成器：惰性产出 bulk action dict ----
        # 类比 Java 的 Iterator<IndexRequest>，ES Python SDK 的 bulk helper 接受 Iterable
        def gen_actions() -> Iterable[dict[str, Any]]:
            for ch, vec in zip(chunks, vectors):
                doc_id_es = f"{task_id}_{ch.chunk_index}"  # 幂等 id
                src: dict[str, Any] = {
                    "id": doc_id_es,
                    "content": ch.text,
                    "embedding": vec,
                    "created_at": now_ms,
                    "doc_id": task_id,
                    "page_number": ch.page,
                    "chunk_index": ch.chunk_index,
                    # v3.5 新增元数据：供后续筛选、诊断和前端展示扩展使用。
                    "doc_name": file_name,
                    "file_type": file_type,
                    "token_count": ch.token_count,
                }
                if scope_private:
                    # 个人知识库索引不含 source_type（与 fish-user-memory 对话事实索引分离）
                    src["user_id"] = user_id or ""

                yield {
                    "_index": index_name,
                    "_id": doc_id_es,
                    "_source": src,
                }

        # ---- 分批 bulk 写入 ----
        actions = list(gen_actions())
        for i in range(0, len(actions), batch_size):
            batch_actions = actions[i : i + batch_size]
            # bulk() 返回 (成功数, 失败列表)
            # raise_on_error=False → 单条错误不抛异常，返回在 failed 列表里
            ok, failed = bulk(self._client, batch_actions, refresh=False, raise_on_error=False)
            if failed:
                log.error("ES bulk failures at offset %s: %s", i, failed)
                # 任意批次失败 → 中断整个任务，之前写入的批次不回滚
                raise RuntimeError(f"Elasticsearch bulk failed at offset {i}: {failed!r}")
            log.debug("ES bulk indexed %s docs (batch slice)", ok)
