"""B2：半批可见性 —— 入库中的文档不应被检索到。

修复设计：chunk 入库时 ready=False（对检索隐藏）；全部 bulk 完成、SUCCESS CAS 成功后再
mark_doc_ready 翻成 True。检索侧用 must_not(term ready=false) 过滤，兼容无 ready 字段的存量切片。
"""

from __future__ import annotations


def _indexer():
    from fish_worker.storage.elasticsearch import ElasticsearchIndexer

    indexer = ElasticsearchIndexer.__new__(ElasticsearchIndexer)
    indexer._client = None
    indexer._s = None
    return indexer


def test_bulk_index_marks_chunks_not_ready(monkeypatch):
    from fish_worker.chunker.text_chunker import TextChunk

    captured = []

    def fake_bulk(client, actions, **kwargs):
        acts = list(actions)
        captured.extend(acts)
        return len(acts), []

    monkeypatch.setattr("fish_worker.storage.elasticsearch.bulk", fake_bulk)
    indexer = _indexer()
    chunks = [TextChunk(text="hi", chunk_index=0, page=1, token_count=2)]

    indexer.bulk_index_document_chunks(
        index_name="fish-user-knowledge",
        task_id="t1",
        scope_private=True,
        user_id="u1",
        file_name="f.pdf",
        file_type="pdf",
        chunks=chunks,
        vectors=[[0.1]],
        batch_size=10,
    )

    assert captured, "bulk 应至少被调用一次"
    # 入库阶段必须 ready=False，避免半批写入时被检索到
    assert all(action["_source"].get("ready") is False for action in captured)


def test_mark_doc_ready_runs_update_by_query_for_doc_id():
    indexer = _indexer()
    upd_calls = []
    refresh_calls = []

    def fake_update_by_query(index, query, **kwargs):
        upd_calls.append({"index": index, "query": query})
        return {"updated": 5}

    fake_indices = type(
        "FakeIndices",
        (),
        {"refresh": staticmethod(lambda index=None, **kw: refresh_calls.append(index))},
    )()
    indexer._client = type(
        "FakeClient",
        (),
        {"update_by_query": staticmethod(fake_update_by_query), "indices": fake_indices},
    )()

    indexer.mark_doc_ready("fish-user-knowledge", "task-123")

    # bulk 用 refresh=False 写入，mark_doc_ready 必须先 refresh，
    # 否则 update_by_query 命中不到刚写入的切片 → 永久 ready=false 不可见
    assert refresh_calls == ["fish-user-knowledge"]
    assert upd_calls, "mark_doc_ready 应调用 update_by_query"
    assert upd_calls[0]["index"] == "fish-user-knowledge"
    assert upd_calls[0]["query"] == {"term": {"doc_id": "task-123"}}


def test_mark_doc_ready_retries_then_raises_when_update_keeps_failing():
    # ES 持续失败时按 max_attempts 重试，耗尽后抛出（让 processor 把任务留在可恢复态）
    import pytest
    from types import SimpleNamespace

    indexer = _indexer()
    indexer._s = SimpleNamespace(
        fish_worker_mark_ready_max_attempts=2, fish_worker_mark_ready_backoff_base=0
    )
    attempts = {"n": 0}

    def boom_update_by_query(index, query, **kwargs):
        attempts["n"] += 1
        raise RuntimeError("ES 5xx")

    fake_indices = type(
        "FakeIndices", (), {"refresh": staticmethod(lambda **kw: None)}
    )()
    indexer._client = type(
        "FakeClient",
        (),
        {"update_by_query": staticmethod(boom_update_by_query), "indices": fake_indices},
    )()

    with pytest.raises(RuntimeError):
        indexer.mark_doc_ready("fish-user-knowledge", "task-x")
    assert attempts["n"] == 2, f"应重试 max_attempts 次，实际 {attempts['n']}"
