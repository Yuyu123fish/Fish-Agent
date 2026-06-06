"""中文/英文句子边界切分工具。"""

from __future__ import annotations

# 句末标点：中文与英文都在这里统一处理。
_ENDERS = set("。？！；.?!;")

# 句末标点后紧跟这些闭合符号时，闭合符号应并入当前句。
_CLOSERS = {
    "\u300d",  # 」
    "\u300f",  # 』
    "\u201d",  # ”
    "\u2019",  # ’
    "\uff09",  # ）
    ")",
    "\u3011",  # 】
    "\u300b",  # 》
    '"',
    "'",
}


def split_sentences(text: str) -> list[str]:
    """按句末标点切分文本，保留尾部无标点句子并丢弃空句。"""
    if not text or not text.strip():
        return []

    sentences: list[str] = []
    buf: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]

        # 英文小数点位于数字之间时不是句末，例如 3.14。
        if ch == "." and 0 < i < n - 1 and text[i - 1].isdigit() and text[i + 1].isdigit():
            buf.append(ch)
            i += 1
            continue

        buf.append(ch)
        if ch in _ENDERS:
            j = i + 1
            while j < n and text[j] in _CLOSERS:
                buf.append(text[j])
                j += 1
            piece = "".join(buf).strip()
            if piece:
                sentences.append(piece)
            buf = []
            i = j
            continue
        i += 1

    tail = "".join(buf).strip()
    if tail:
        sentences.append(tail)
    return sentences
