"""结构感知分块：按标题、正文、表格采用不同切分策略。"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from fish_worker.chunker.sentence_splitter import split_sentences
from fish_worker.chunker.text_chunker import TextChunk, ENCODER, chunk_tokens, count_tokens
from fish_worker.parser.base import RawElement

log = logging.getLogger(__name__)


@dataclass
class _Segment:
    """内部段落模型：把 parser 的 RawElement 流先规整为正文段或表格段。"""

    kind: str
    page: int | None
    text: str = ""
    rows: list[str] | None = None


def chunk_elements(
    elements: list[RawElement],
    *,
    chunk_size: int = 512,
    overlap: int = 50,
    table_max_tokens: int = 1024,
) -> list[TextChunk]:
    """结构化分块入口，输出保持为 TextChunk 以兼容下游。"""
    if not elements:
        return []

    chunks: list[TextChunk] = []
    next_index = 0
    for segment in _build_segments(elements):
        if segment.kind == "table":
            produced = _chunk_table(segment.rows or [], segment.page, next_index, chunk_size, table_max_tokens)
        else:
            produced = _chunk_text(segment.text, segment.page, next_index, chunk_size, overlap)
        chunks.extend(produced)
        next_index += len(produced)

    log.info("structured chunk produced %s chunks from %s raw elements", len(chunks), len(elements))
    return chunks


def _build_segments(elements: list[RawElement]) -> list[_Segment]:
    """将连续同页 Title/Text 合并为正文段，连续同页 Table 合并为表格段。"""
    segments: list[_Segment] = []
    i = 0
    while i < len(elements):
        current = elements[i]
        page = current.page
        if current.element_type == "Table":
            rows = [current.text]
            i += 1
            while i < len(elements) and elements[i].element_type == "Table" and elements[i].page == page:
                rows.append(elements[i].text)
                i += 1
            segments.append(_Segment(kind="table", page=page, rows=_clean_parts(rows)))
            continue

        parts = [current.text]
        i += 1
        while i < len(elements) and elements[i].element_type != "Table" and elements[i].page == page:
            parts.append(elements[i].text)
            i += 1
        text = "\n".join(_clean_parts(parts))
        if text.strip():
            segments.append(_Segment(kind="text", page=page, text=text))
    return segments


def _chunk_text(text: str, page: int | None, start_index: int, chunk_size: int, overlap: int) -> list[TextChunk]:
    """按句子边界贪心打包；单句超长时回退 token 滑窗。"""
    if not text or not text.strip():
        return []

    chunks: list[TextChunk] = []
    current: list[str] = []
    current_tokens = 0
    for sentence in split_sentences(text):
        sentence_tokens = count_tokens(sentence)
        if sentence_tokens > chunk_size:
            chunks.extend(_emit_current(current, page, start_index + len(chunks)))
            current = []
            current_tokens = 0
            chunks.extend(_chunk_oversized_text(sentence, page, start_index + len(chunks), chunk_size, overlap))
            continue

        if current and current_tokens + sentence_tokens > chunk_size:
            chunks.extend(_emit_current(current, page, start_index + len(chunks)))
            current = _overlap_tail(current, overlap)
            current_tokens = count_tokens("".join(current))

        current.append(sentence)
        current_tokens += sentence_tokens

    chunks.extend(_emit_current(current, page, start_index + len(chunks)))
    return chunks


def _chunk_table(
    rows: list[str],
    page: int | None,
    start_index: int,
    chunk_size: int,
    table_max_tokens: int,
) -> list[TextChunk]:
    """表格优先整表保留；超限时按行组切分，每组重复表头。"""
    if not rows:
        return []

    whole = "\n".join(rows)
    if count_tokens(whole) <= table_max_tokens:
        return [_make_chunk(whole, page, start_index)]

    header = rows[0]
    chunks: list[TextChunk] = []
    group: list[str] = [header]
    for row in rows[1:]:
        candidate = "\n".join(group + [row])
        if len(group) > 1 and count_tokens(candidate) > chunk_size:
            chunks.extend(_emit_table_group(group, page, start_index + len(chunks), chunk_size))
            group = [header, row]
        else:
            group.append(row)
    chunks.extend(_emit_table_group(group, page, start_index + len(chunks), chunk_size))
    return chunks


def _emit_current(parts: list[str], page: int | None, start_index: int) -> list[TextChunk]:
    text = "".join(parts).strip()
    return [_make_chunk(text, page, start_index)] if text else []


def _emit_table_group(group: list[str], page: int | None, start_index: int, chunk_size: int) -> list[TextChunk]:
    text = "\n".join(group).strip()
    if not text:
        return []
    # 表格块优先保留“表头 + 行”的结构；极端情况下宁可单块略超，也不把表头切丢。
    return [_make_chunk(text, page, start_index)]


def _chunk_oversized_text(
    text: str,
    page: int | None,
    start_index: int,
    chunk_size: int,
    overlap: int,
) -> list[TextChunk]:
    tokens = ENCODER.encode(text)
    out: list[TextChunk] = []
    for piece, token_count in chunk_tokens(tokens, ENCODER, chunk_size, overlap):
        if piece.strip():
            out.append(TextChunk(text=piece.strip(), chunk_index=start_index + len(out), page=page, token_count=token_count))
    return out


def _overlap_tail(sentences: list[str], overlap: int) -> list[str]:
    """取尾部若干完整句作为重叠区，避免在句中制造重复。"""
    if overlap <= 0:
        return []
    selected: list[str] = []
    total = 0
    for sentence in reversed(sentences):
        token_count = count_tokens(sentence)
        if selected and total + token_count > overlap:
            break
        selected.insert(0, sentence)
        total += token_count
        if total >= overlap:
            break
    return selected


def _make_chunk(text: str, page: int | None, chunk_index: int) -> TextChunk:
    return TextChunk(text=text.strip(), chunk_index=chunk_index, page=page, token_count=count_tokens(text))


def _clean_parts(parts: list[str]) -> list[str]:
    return [part.strip() for part in parts if part and part.strip()]
