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


class FakeMinio:
    def get_object(self, path: str) -> tuple[bytes, str]:
        return b"data", "application/pdf"


class FakeDb:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, str | None]] = []

    def update_status(self, task_id: str, status: str, **kwargs) -> int:
        self.calls.append((task_id, status, kwargs.get("expected_status")))
        if status == "SUCCESS" and kwargs.get("expected_status") == "PROCESSING":
            return 0
        return 1

    def touch(self, task_id: str) -> int:
        return 1

    def close_current_thread_conn(self) -> None:
        return None


class FakeEs:
    def __init__(self) -> None:
        self.deletes: list[tuple[str, str]] = []
        self.bulk_calls = 0

    def delete_by_doc_id(self, index_name: str, task_id: str) -> None:
        self.deletes.append((index_name, task_id))

    def bulk_index_document_chunks(self, **kwargs) -> None:
        self.bulk_calls += 1


class FakeEmbedder:
    def embed_batch(self, texts: list[str]) -> list[list[float]]:
        return [[0.1, 0.2, 0.3] for _ in texts]


def fake_ctx(db: FakeDb, es: FakeEs):
    settings = SimpleNamespace(
        fish_worker_heartbeat_seconds=3600,
        fish_worker_chunk_size=512,
        fish_worker_chunk_overlap=50,
        fish_worker_es_batch_size=20,
        knowledge_user_index="fish-user-knowledge",
        knowledge_public_index="fish-public-knowledge",
    )
    return SimpleNamespace(
        settings=settings,
        minio=FakeMinio(),
        db=db,
        es=es,
        embedder=FakeEmbedder(),
    )


class IngestProcessorCasTest(unittest.TestCase):
    def test_cleans_es_when_terminal_success_loses_cas(self) -> None:
        db = FakeDb()
        es = FakeEs()
        task = IngestTask(
            task_id="task-1",
            minio_path="docs/a.pdf",
            scope_type="PRIVATE",
            user_id="user-1",
            file_name="a.pdf",
        )

        with (
            patch("fish_worker.processor.ParserFactory.get", return_value=FakeParser()),
            patch(
                "fish_worker.processor.chunk_elements",
                return_value=[TextChunk(text="hello", chunk_index=0, page=1, token_count=1)],
            ),
        ):
            IngestProcessor(fake_ctx(db, es)).process(task)

        self.assertEqual(1, es.bulk_calls)
        self.assertEqual(
            [("fish-user-knowledge", "task-1"), ("fish-user-knowledge", "task-1")],
            es.deletes,
        )
        self.assertIn(("task-1", "SUCCESS", "PROCESSING"), db.calls)


if __name__ == "__main__":
    unittest.main()
