from fish_worker.chunker.contextual_indexing import apply_contextual_prefixes
from fish_worker.chunker.text_chunker import TextChunk


def test_apply_contextual_prefixes_uses_generator_output() -> None:
    chunks = [TextChunk(text="第二章内容", chunk_index=0, page=1, token_count=5)]

    enriched = apply_contextual_prefixes(
        chunks,
        document_text="标题\n第一章\n第二章内容",
        enabled=True,
        generator=lambda document_text, chunk: "本文档标题为标题，本块属于第二章。",
    )

    assert enriched[0].context_prefix == "本文档标题为标题，本块属于第二章。"
    assert enriched[0].contextualized_text.startswith("本文档标题为标题")
    assert enriched[0].contextualized_text.endswith("第二章内容")


def test_apply_contextual_prefixes_is_noop_when_disabled() -> None:
    chunks = [TextChunk(text="原文", chunk_index=0, page=None, token_count=2)]

    enriched = apply_contextual_prefixes(chunks, "原文", enabled=False)

    assert enriched[0].context_prefix == ""
    assert enriched[0].contextualized_text is None
