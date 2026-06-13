"""Contextual indexing helpers for document chunks.

The production path can later replace the generator with an LLM call. This
module keeps the mutation and fallback rules deterministic so tests and worker
retries stay stable.
"""

from __future__ import annotations

from collections.abc import Callable

from fish_worker.chunker.text_chunker import TextChunk

ContextGenerator = Callable[[str, TextChunk], str]


def apply_contextual_prefixes(
    chunks: list[TextChunk],
    document_text: str,
    *,
    enabled: bool,
    generator: ContextGenerator | None = None,
    fallback_title: str = "",
) -> list[TextChunk]:
    """Attach a short document-aware prefix to every chunk.

    The prefix is embedded together with the chunk and written to
    ``contextualized_content``. ``content`` remains the original chunk text so
    rendering and audits still show exactly what came from the document.
    """
    if not enabled:
        for chunk in chunks:
            chunk.context_prefix = ""
            chunk.contextualized_text = None
        return chunks

    for chunk in chunks:
        prefix = ""
        if generator is not None:
            prefix = (generator(document_text, chunk) or "").strip()
        if not prefix:
            prefix = _fallback_prefix(document_text, chunk, fallback_title)
        chunk.context_prefix = prefix
        chunk.contextualized_text = f"{prefix}\n\n{chunk.text}".strip() if prefix else chunk.text
    return chunks


def _fallback_prefix(document_text: str, chunk: TextChunk, fallback_title: str) -> str:
    title = (fallback_title or "").strip()
    if not title:
        first_line = next((line.strip() for line in document_text.splitlines() if line.strip()), "")
        title = first_line[:80]
    page_part = f"第{chunk.page}页" if chunk.page is not None else "页码未知"
    return f"本文档标题为{title or '未知'}，本块位于{page_part}，chunk序号为{chunk.chunk_index}。"
