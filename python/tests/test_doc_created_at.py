"""B3：doc_created_at 应使用文档真实创建日期，而非上传时间。

覆盖三个可测单元：
  - parse_pdf_date：PDF 元数据日期串 → datetime（纯函数）
  - DocxParser.created_at：python-docx core_properties.created
  - ElasticsearchIndexer.bulk_index_document_chunks：写入时用传入的 doc_created_at_ms
"""

from __future__ import annotations

import io
import time
from datetime import datetime, timezone

import pytest


# ---------- parse_pdf_date（纯函数）----------
def test_parse_pdf_date_handles_utc_zulu():
    from fish_worker.parser.pdf import parse_pdf_date

    expected = datetime(2026, 1, 1, 12, 0, 0, tzinfo=timezone.utc).timestamp()
    got = parse_pdf_date("D:20260101120000Z")
    assert got is not None
    assert got.timestamp() == pytest.approx(expected)


def test_parse_pdf_date_offset_points_to_same_instant():
    from fish_worker.parser.pdf import parse_pdf_date

    # 本地 12:00(+08:00) 与 12:00 UTC 是不同时刻；+08 的 12:00 == UTC 04:00
    expected = datetime(2026, 1, 1, 4, 0, 0, tzinfo=timezone.utc).timestamp()
    got = parse_pdf_date("D:20260101120000+08'00'")
    assert got is not None
    assert got.timestamp() == pytest.approx(expected)


def test_parse_pdf_date_none_for_garbage():
    from fish_worker.parser.pdf import parse_pdf_date

    assert parse_pdf_date("not a date") is None
    assert parse_pdf_date(None) is None
    assert parse_pdf_date("") is None


# ---------- DocxParser.created_at ----------
def test_docx_parser_extracts_core_properties_created():
    from docx import Document

    from fish_worker.parser.docx_parser import DocxParser

    doc = Document()
    doc.add_paragraph("hello world")
    doc.core_properties.created = datetime(2025, 6, 15, 10, 30, 0)
    buf = io.BytesIO()
    doc.save(buf)

    got = DocxParser().created_at(buf.getvalue(), "x.docx")
    assert got is not None
    # python-docx 回读 created 可能丢失时区，按日期粒度断言（recency 只关心粗粒度）
    assert (got.year, got.month, got.day) == (2025, 6, 15)


# ---------- bulk_index_document_chunks：doc_created_at ----------
def _capture_bulk(monkeypatch):
    captured = []

    def fake_bulk(client, actions, **kwargs):
        acts = list(actions)
        captured.extend(acts)
        return len(acts), []

    monkeypatch.setattr("fish_worker.storage.elasticsearch.bulk", fake_bulk)
    return captured


def _indexer():
    from fish_worker.storage.elasticsearch import ElasticsearchIndexer

    indexer = ElasticsearchIndexer.__new__(ElasticsearchIndexer)
    indexer._client = None
    indexer._s = None
    return indexer


def test_bulk_index_uses_provided_doc_created_at(monkeypatch):
    from fish_worker.chunker.text_chunker import TextChunk

    captured = _capture_bulk(monkeypatch)
    indexer = _indexer()
    chunks = [TextChunk(text="hello", chunk_index=0, page=1, token_count=5)]

    indexer.bulk_index_document_chunks(
        index_name="fish-user-knowledge",
        task_id="t1",
        scope_private=True,
        user_id="u1",
        file_name="f.pdf",
        file_type="pdf",
        chunks=chunks,
        vectors=[[0.1, 0.2]],
        batch_size=10,
        default_authority=0.7,
        doc_created_at_ms=1_700_000_000_000,
    )

    assert captured, "bulk 应至少被调用一次"
    assert captured[0]["_source"]["doc_created_at"] == 1_700_000_000_000


def test_bulk_index_falls_back_to_now_without_doc_created_at(monkeypatch):
    from fish_worker.chunker.text_chunker import TextChunk

    captured = _capture_bulk(monkeypatch)
    indexer = _indexer()
    chunks = [TextChunk(text="hi", chunk_index=0, page=1, token_count=2)]

    before = int(time.time() * 1000)
    indexer.bulk_index_document_chunks(
        index_name="fish-public-knowledge",
        task_id="t2",
        scope_private=False,
        user_id=None,
        file_name="f.pdf",
        file_type="pdf",
        chunks=chunks,
        vectors=[[0.1]],
        batch_size=10,
    )
    after = int(time.time() * 1000)

    assert captured, "bulk 应至少被调用一次"
    doc_created_at = captured[0]["_source"]["doc_created_at"]
    assert before <= doc_created_at <= after, f"无真实日期时应回退到当前时间，实际 {doc_created_at}"


def test_parse_pdf_date_handles_no_offset_as_utc():
    # PDF 1.7 偏移可选；无偏移（D:YYYYMMDDHHmmss）应按 UTC 解析，而非回退到入库时间
    from fish_worker.parser.pdf import parse_pdf_date

    expected = datetime(2025, 6, 15, 10, 30, 0, tzinfo=timezone.utc).timestamp()
    got = parse_pdf_date("D:20250615103000")
    assert got is not None
    assert got.timestamp() == pytest.approx(expected)


def test_parse_pdf_date_rejects_trailing_junk():
    from fish_worker.parser.pdf import parse_pdf_date

    assert parse_pdf_date("D:20250615103000Z00") is None
    assert parse_pdf_date("D:20250615103000extra") is None
