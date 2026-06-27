from __future__ import annotations

import unittest
from types import SimpleNamespace
from unittest.mock import patch

from fish_worker.chunker.text_chunker import TextChunk
from fish_worker.parser.base import RawElement
from fish_worker.processor import IngestProcessor, IngestTask


class FakeParser:
    def parse(self, content: bytes, filename: str, tmp_dir: str | None = None) -> list[RawElement]:
        return [RawElement(text="hello", page=1, element_type="Text")]

    def created_at(self, content: bytes, filename: str):
        # B3 SPI 默认：无元数据的格式返回 None，回退到入库时间
        return None


class FakeMinio:
    def get_object(self, path: str) -> tuple[bytes, str]:
        return b"data", "application/pdf"


class FakeDb:
    """success_cas_returns：SUCCESS/PROCESSING CAS 的返回值（affected 行数）。
    默认 0 = CAS 失败（补偿竞态场景）；happy-path 测试传 1。"""

    def __init__(self, success_cas_returns: int = 0) -> None:
        self.calls: list[tuple[str, str, str | None]] = []
        self.success_cas_returns = success_cas_returns

    def update_status(self, task_id: str, status: str, **kwargs) -> int:
        self.calls.append((task_id, status, kwargs.get("expected_status")))
        if status == "SUCCESS" and kwargs.get("expected_status") == "PROCESSING":
            return self.success_cas_returns
        return 1

    def touch(self, task_id: str) -> int:
        return 1

    def close_current_thread_conn(self) -> None:
        return None


class FakeEs:
    def __init__(self, mark_ready_raises: bool = False) -> None:
        self.deletes: list[tuple[str, str]] = []
        self.bulk_calls = 0
        self.last_bulk_kwargs: dict | None = None
        self.mark_ready_calls: list[tuple[str, str]] = []
        self._mark_ready_raises = mark_ready_raises

    def delete_by_doc_id(self, index_name: str, task_id: str) -> None:
        self.deletes.append((index_name, task_id))

    def bulk_index_document_chunks(self, **kwargs) -> None:
        self.bulk_calls += 1
        self.last_bulk_kwargs = kwargs

    def mark_doc_ready(self, index_name: str, doc_id: str) -> None:
        self.mark_ready_calls.append((index_name, doc_id))
        if self._mark_ready_raises:
            raise RuntimeError("mark_doc_ready boom")


class FakeEmbedder:
    def embed_batch(self, texts: list[str]) -> list[list[float]]:
        return [[0.1, 0.2, 0.3] for _ in texts]


def fake_ctx(db: FakeDb, es: FakeEs):
    settings = SimpleNamespace(
        fish_worker_heartbeat_seconds=3600,
        fish_worker_chunk_size=512,
        fish_worker_chunk_overlap=50,
        fish_worker_chunk_strategy="flat",
        fish_worker_table_max_tokens=1024,
        fish_worker_es_batch_size=20,
        knowledge_user_index="fish-user-knowledge",
        knowledge_public_index="fish-public-knowledge",
        # processor 访问的 RAG 设置（补齐，避免 SimpleNamespace AttributeError）
        fish_rag_contextual_indexing_enabled=False,
        fish_rag_authority_private=0.7,
        fish_rag_authority_public=1.0,
        # mark_doc_ready 重试（测试用单次、零退避）
        fish_worker_mark_ready_max_attempts=1,
        fish_worker_mark_ready_backoff_base=0.0,
    )
    return SimpleNamespace(
        settings=settings,
        minio=FakeMinio(),
        db=db,
        es=es,
        embedder=FakeEmbedder(),
    )


def _patch_chunk():
    return patch(
        "fish_worker.processor.chunk_elements",
        return_value=[TextChunk(text="hello", chunk_index=0, page=1, token_count=1)],
    )


def _private_task(task_id: str) -> IngestTask:
    return IngestTask(
        task_id=task_id, minio_path="docs/a.pdf", scope_type="PRIVATE",
        user_id="user-1", file_name="a.pdf",
    )


class IngestProcessorCasTest(unittest.TestCase):
    def test_cleans_es_when_terminal_success_loses_cas(self) -> None:
        db = FakeDb()
        es = FakeEs()
        with (
            patch("fish_worker.processor.ParserFactory.get", return_value=FakeParser()),
            _patch_chunk(),
        ):
            IngestProcessor(fake_ctx(db, es)).process(_private_task("task-1"))

        self.assertEqual(1, es.bulk_calls)
        self.assertEqual("pdf", es.last_bulk_kwargs["file_type"])
        # CAS 失败 → cleanup 再删一次（写入前的幂等删除 + 失败后清理删除）
        self.assertEqual(
            [("fish-user-knowledge", "task-1"), ("fish-user-knowledge", "task-1")],
            es.deletes,
        )
        self.assertIn(("task-1", "SUCCESS", "PROCESSING"), db.calls)

    def test_cleans_parser_elements_before_chunking(self) -> None:
        db = FakeDb()
        es = FakeEs()
        observed_texts: list[str] = []

        def fake_chunk_elements(elements: list[RawElement], **kwargs) -> list[TextChunk]:
            observed_texts.extend(elem.text for elem in elements)
            return [TextChunk(text="clean chunk", chunk_index=0, page=1, token_count=2)]

        with (
            patch(
                "fish_worker.processor.ParserFactory.get",
                return_value=type(
                    "DirtyParser",
                    (),
                    {
                        "parse": lambda self, content, filename, tmp_dir=None: [
                            RawElement(text="ﬁle(cid:9)。。。", page=1, element_type="Text")
                        ],
                        "created_at": lambda self, content, filename: None,
                    },
                )(),
            ),
            patch("fish_worker.processor.chunk_elements", side_effect=fake_chunk_elements),
        ):
            IngestProcessor(fake_ctx(db, es)).process(_private_task("task-2"))

        self.assertEqual(["file。"], observed_texts)

    def test_marks_success_when_cleaning_removes_all_elements(self) -> None:
        db = FakeDb()
        es = FakeEs()
        with (
            patch(
                "fish_worker.processor.ParserFactory.get",
                return_value=type(
                    "ControlOnlyParser",
                    (),
                    {
                        "parse": lambda self, content, filename, tmp_dir=None: [
                            RawElement(text="\x00​", page=1, element_type="Text")
                        ],
                        "created_at": lambda self, content, filename: None,
                    },
                )(),
            ),
            patch("fish_worker.processor.chunk_elements") as chunk_mock,
        ):
            IngestProcessor(fake_ctx(db, es)).process(_private_task("task-3"))

        chunk_mock.assert_not_called()
        self.assertIn(("task-3", "SUCCESS", "PROCESSING"), db.calls)

    # ---- B2：mark_doc_ready 接线（happy-path PRIVATE/PUBLIC + 失败路径）----

    def test_marks_doc_ready_on_success_private(self) -> None:
        db = FakeDb(success_cas_returns=1)
        es = FakeEs()
        with (
            patch("fish_worker.processor.ParserFactory.get", return_value=FakeParser()),
            _patch_chunk(),
        ):
            IngestProcessor(fake_ctx(db, es)).process(_private_task("task-p"))

        self.assertEqual([("fish-user-knowledge", "task-p")], es.mark_ready_calls)
        self.assertIn(("task-p", "SUCCESS", "PROCESSING"), db.calls)

    def test_marks_doc_ready_on_success_public(self) -> None:
        db = FakeDb(success_cas_returns=1)
        es = FakeEs()
        task = IngestTask(
            task_id="task-u", minio_path="docs/a.pdf", scope_type="PUBLIC",
            user_id="admin", file_name="a.pdf",
        )
        with (
            patch("fish_worker.processor.ParserFactory.get", return_value=FakeParser()),
            _patch_chunk(),
        ):
            IngestProcessor(fake_ctx(db, es)).process(task)

        self.assertEqual([("fish-public-knowledge", "task-u")], es.mark_ready_calls)
        self.assertIn(("task-u", "SUCCESS", "PROCESSING"), db.calls)

    def test_fails_when_mark_doc_ready_raises(self) -> None:
        # mark_doc_ready 失败 → 任务转 FAILED（可恢复/可重传），绝不静默 SUCCESS 但永久不可见
        db = FakeDb(success_cas_returns=1)
        es = FakeEs(mark_ready_raises=True)
        with (
            patch("fish_worker.processor.ParserFactory.get", return_value=FakeParser()),
            _patch_chunk(),
        ):
            with self.assertRaises(RuntimeError):
                IngestProcessor(fake_ctx(db, es)).process(_private_task("task-f"))

        statuses = [status for (tid, status, _) in db.calls if tid == "task-f"]
        self.assertIn("FAILED", statuses)
        self.assertNotIn("SUCCESS", statuses)
        self.assertEqual(1, es.bulk_calls)  # chunks 已写（ready=false）
        self.assertTrue(es.mark_ready_calls)  # mark_doc_ready 被调用并抛出


if __name__ == "__main__":
    unittest.main()
