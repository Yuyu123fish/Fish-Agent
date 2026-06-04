"""Shared byte decoding helpers for text-like document parsers."""

from __future__ import annotations

_DEFAULT_ENCODINGS = ["utf-8-sig", "utf-8", "gbk", "gb2312", "gb18030", "big5"]


def decode_bytes(content: bytes, extra: list[str] | None = None) -> str:
    """Decode bytes with UTF-8 first, common Chinese encodings next, and latin-1 last."""
    candidates: list[str] = []
    for enc in list(extra or []) + _DEFAULT_ENCODINGS:
        if enc and enc not in candidates:
            candidates.append(enc)

    for enc in candidates:
        try:
            return content.decode(enc).lstrip("\ufeff")
        except (LookupError, UnicodeDecodeError):
            continue
    return content.decode("latin-1")
