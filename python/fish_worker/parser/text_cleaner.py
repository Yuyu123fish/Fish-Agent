"""Stateless text cleanup pipeline for OCR and parser output."""

from __future__ import annotations

import logging
import re
import unicodedata

from fish_worker.parser.base import RawElement

log = logging.getLogger(__name__)

# Conservative OCR/layout fixes. Add new rules only when a real OCR pattern proves safe.
_OCR_FIXES: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"ﬁ"), "fi"),
    (re.compile(r"ﬂ"), "fl"),
    (re.compile(r"ﬀ"), "ff"),
    (re.compile(r"ﬃ"), "ffi"),
    (re.compile(r"ﬄ"), "ffl"),
    (re.compile(r"\(cid:\d+\)"), ""),
]

_ZERO_WIDTH = ["\u200b", "\u200c", "\u200d", "\ufeff"]
_KEEP_CONTROL = {"\n", "\r", "\t"}


def normalize_unicode(text: str) -> str:
    """Normalize combining character sequences without changing visual text."""
    return unicodedata.normalize("NFC", text)


def remove_control_chars(text: str) -> str:
    """Remove invisible control/format characters while preserving text whitespace."""
    for ch in _ZERO_WIDTH:
        text = text.replace(ch, "")

    out: list[str] = []
    for ch in text:
        if ch in _KEEP_CONTROL:
            out.append(ch)
            continue
        if unicodedata.category(ch) in {"Cc", "Cf"}:
            continue
        out.append(ch)
    return "".join(out)


def fix_common_ocr_errors(text: str) -> str:
    """Apply conservative OCR artifact replacements."""
    for pattern, replacement in _OCR_FIXES:
        text = pattern.sub(replacement, text)
    return text


def collapse_punctuation(text: str) -> str:
    """Collapse repeated punctuation while preserving ordinary punctuation semantics."""
    text = re.sub(r"。{2,}", "。", text)
    text = re.sub(r"，{2,}", "，", text)
    text = re.sub(r"；{2,}", "；", text)
    text = re.sub(r"！{2,}", "！", text)
    text = re.sub(r"？{2,}", "？", text)
    text = re.sub(r"\.{3,}", "…", text)
    return text


def normalize_whitespace(text: str) -> str:
    """Normalize spaces while preserving paragraph boundaries."""
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def clean_ocr_text(text: str) -> str:
    """Run the full cleanup pipeline for one text segment."""
    text = normalize_unicode(text)
    text = remove_control_chars(text)
    text = fix_common_ocr_errors(text)
    text = collapse_punctuation(text)
    text = normalize_whitespace(text)
    return text


def clean_elements(elements: list[RawElement]) -> list[RawElement]:
    """Clean parsed elements and drop entries that become empty after cleanup."""
    result: list[RawElement] = []
    for elem in elements:
        cleaned = clean_ocr_text(elem.text)
        if cleaned:
            result.append(RawElement(text=cleaned, page=elem.page, element_type=elem.element_type))
        elif log.isEnabledFor(logging.DEBUG):
            log.debug("raw element cleaned to empty: %r", elem.text[:80])
    return result
