# 基于 Token 的文本分块（tiktoken cl100k_base 编码器）
#
# 为什么用 token 而不是字符数：
#   LLM 按 token 计费和限制上下文窗口，用 token 能精确控制每块大小
#   tiktoken 是 OpenAI 官方 tokenizer，与 DashScope / GPT-4 对齐
#
# flat 兼容分块策略：
#   1. 同页元素拼接（用两个换行分隔），不跨页合并
#   2. 拼接后的文本用 tiktoken encode → token 列表
#   3. 按 chunk_size=512 token 切分，overlap=50 token 重叠
#   4. 去掉空白块，输出 TextChunk 列表
#
# 重叠 (overlap) 的意义：
#   相邻块共享 50 token，避免关键信息恰好落在边界被截断
#   类比分页查询时故意让相邻页有 1-2 条重复数据
"""Token-based chunking with overlap (tiktoken cl100k_base)."""

from __future__ import annotations

import logging
from dataclasses import dataclass

import tiktoken

from fish_worker.parser.base import RawElement

log = logging.getLogger(__name__)

# 模块级常量：encoder 只初始化一次（tiktoken 内部也有缓存，但显式提取更清晰）
ENCODER = tiktoken.get_encoding("cl100k_base")


def count_tokens(text: str) -> int:
    """统计文本的 cl100k_base token 数，供 flat/structured 两种分块策略复用。"""
    return len(ENCODER.encode(text))


@dataclass
class TextChunk:
    """单个文本分块 —— 写入 ES 的最小单元。

    chunk_index: 全局自增序号（跨页）
    page:        来源页码（None 表示未知）
    token_count:  实际 token 数（chunk_size 可能不足 512）
    """
    text: str
    chunk_index: int
    page: int | None
    token_count: int


def chunk_tokens(
    tokens: list[int],
    encoder,
    chunk_size: int,
    overlap: int,
) -> list[tuple[str, int]]:
    """把 token 列表按固定大小切分为 (decoded_text, token_count) 列表。

    滑动窗口：每次前进 chunk_size - overlap，保证相邻块有 overlap 个 token 重叠。
    start = max(end - overlap, start + 1) 保证 start 始终前进（防止 overlap >= chunk_size 时死循环）。
    """
    out: list[tuple[str, int]] = []
    start = 0
    n = len(tokens)
    while start < n:
        end = min(start + chunk_size, n)
        piece = encoder.decode(tokens[start:end])  # token → 可读文本
        out.append((piece, end - start))
        if end >= n:
            break
        start = max(end - overlap, start + 1)
    return out


def chunk_elements(
    elements: list[RawElement],
    *,
    chunk_size: int = 512,
    overlap: int = 50,
) -> list[TextChunk]:
    """主入口：把解析器产出的 RawElement 列表分块。

    分组逻辑：按 page 分组 → 组内拼接 → tokenize → 滑动窗口切分
    """
    # ---- 按页码分组 ----
    groups: dict[int | None, list[RawElement]] = {}
    for el in elements:
        # dict.setdefault(key, default) 等价于：
        #   if key not in dict: dict[key] = default
        #   return dict[key]
        groups.setdefault(el.page, []).append(el)

    chunks: list[TextChunk] = []
    global_idx = 0  # 跨页全局递增

    # 排序：有页码的在前（按页码升序），无页码的（None）放最后
    def page_sort_key(p: int | None) -> tuple[int, int]:
        if p is None:
            return (1, 0)
        return (0, p)

    for page in sorted(groups.keys(), key=page_sort_key):
        page_els = groups[page]

        # 同页元素用空行拼接（保留段落边界）
        merged_parts: list[str] = []
        for el in page_els:
            merged_parts.append(el.text)
        merged = "\n\n".join(merged_parts)

        if not merged.strip():
            continue

        # tokenize → 切分
        tokens = ENCODER.encode(merged)
        for text_piece, tc in chunk_tokens(tokens, ENCODER, chunk_size, overlap):
            if not text_piece.strip():
                continue
            chunks.append(
                TextChunk(text=text_piece.strip(), chunk_index=global_idx, page=page, token_count=tc)
            )
            global_idx += 1

    log.info("chunk_elements produced %s chunks from %s raw elements", len(chunks), len(elements))
    return chunks
