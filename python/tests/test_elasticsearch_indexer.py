from __future__ import annotations

import unittest
from types import SimpleNamespace
from unittest.mock import patch

from fish_worker.chunker.text_chunker import TextChunk
from fish_worker.storage.elasticsearch import ElasticsearchIndexer


class ElasticsearchIndexerMetadataTest(unittest.TestCase):
    def _capture_bulk(self) -> tuple[list[dict], object]:
        """公共辅助：创建 fake_bulk + fake settings，返回 (captured, fake_bulk_func)。"""
        captured: list[dict] = []

        def fake_bulk(client, actions, **kwargs):
            captured.extend(actions)
            return len(actions), []

        settings = SimpleNamespace(
            elasticsearch_username="",
            elasticsearch_password="",
            es_hosts=["http://localhost:9200"],
        )
        return captured, fake_bulk, settings

    def test_bulk_source_contains_document_metadata_for_private_scope(self) -> None:
        captured, fake_bulk, settings = self._capture_bulk()

        with (
            patch("fish_worker.storage.elasticsearch.Elasticsearch"),
            patch("fish_worker.storage.elasticsearch.bulk", side_effect=fake_bulk),
        ):
            indexer = ElasticsearchIndexer(settings)
            indexer.bulk_index_document_chunks(
                index_name="fish-user-knowledge",
                task_id="task-1",
                scope_private=True,
                user_id="user-1",
                file_name="demo.pdf",
                file_type="pdf",
                chunks=[TextChunk(text="hello", chunk_index=0, page=1, token_count=3)],
                vectors=[[0.1, 0.2]],
                batch_size=20,
            )

        source = captured[0]["_source"]
        self.assertEqual("demo.pdf", source["doc_name"])
        self.assertEqual("pdf", source["file_type"])
        self.assertEqual(3, source["token_count"])
        self.assertEqual("user-1", source["user_id"])

    def test_bulk_source_public_scope_has_no_user_id(self) -> None:
        captured, fake_bulk, settings = self._capture_bulk()

        with (
            patch("fish_worker.storage.elasticsearch.Elasticsearch"),
            patch("fish_worker.storage.elasticsearch.bulk", side_effect=fake_bulk),
        ):
            indexer = ElasticsearchIndexer(settings)
            indexer.bulk_index_document_chunks(
                index_name="fish-public-knowledge",
                task_id="task-2",
                scope_private=False,
                user_id=None,
                file_name="guide.docx",
                file_type="docx",
                chunks=[TextChunk(text="public doc", chunk_index=0, page=1, token_count=5)],
                vectors=[[0.3, 0.4]],
                batch_size=20,
            )

        source = captured[0]["_source"]
        self.assertEqual("guide.docx", source["doc_name"])
        self.assertEqual("docx", source["file_type"])
        self.assertEqual(5, source["token_count"])
        self.assertNotIn("user_id", source)


if __name__ == "__main__":
    unittest.main()
