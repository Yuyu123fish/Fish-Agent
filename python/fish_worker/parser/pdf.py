# PDF 解析器 — 文本层优先，质量不合格时回退 OCR。
#
# 背景：
#   文字型 PDF 直接 get_text() 速度是毫秒级；扫描件或 CMap 损坏 PDF 需要 OCR。
#   本解析器先做文本层质量判断，干净文本直接输出；短文本、CID 残留、乱码占比过高时回退
#   PyMuPDF 300 DPI 渲染 + pytesseract (chi_sim+eng)。
"""Hybrid PDF parsing: text layer first, OCR fallback when quality is poor."""

from __future__ import annotations

import io
import logging
import os
import re
import tempfile

import fitz  # PyMuPDF
import pytesseract
from PIL import Image

from fish_worker.parser.base import BaseParser, RawElement

# Windows 上 Tesseract 可能不在系统 PATH 中，尝试常见安装路径。
import os as _os

_possible_paths = [
    r"D:\develope\Tesseract-OCR\tesseract.exe",
    r"C:\Program Files\Tesseract-OCR\tesseract.exe",
]
for _p in _possible_paths:
    if _os.path.exists(_p):
        pytesseract.pytesseract.tesseract_cmd = _p
        break

log = logging.getLogger(__name__)

_MIN_TEXT_CHARS = 50
_CID_RATIO_THRESHOLD = 0.05
_GARBLED_RATIO_THRESHOLD = 0.05
_CID_PATTERN = re.compile(r"\(cid:\d+\)")


def count_cid_residuals(text: str) -> int:
    """统计 (cid:N) 残留字符数，这是 PDF 字体映射损坏的典型信号。"""
    return sum(len(match.group(0)) for match in _CID_PATTERN.finditer(text or ""))


def has_garbled_unicode(text: str, threshold: float = _GARBLED_RATIO_THRESHOLD) -> bool:
    """检测替换符和私用区字符占比，超过阈值说明文本层质量较差。"""
    if not text:
        return False
    bad = sum(1 for ch in text if ch == "\ufffd" or "\ue000" <= ch <= "\uf8ff")
    return bad / len(text) > threshold


def should_use_ocr(
    text: str,
    *,
    min_chars: int = _MIN_TEXT_CHARS,
    cid_ratio: float = _CID_RATIO_THRESHOLD,
) -> bool:
    """判断 PDF 文本层是否需要回退 OCR。阈值偏保守，宁可多 OCR 也不写入乱码。"""
    stripped = (text or "").strip()
    if len(stripped) < min_chars:
        return True
    if count_cid_residuals(stripped) / max(1, len(stripped)) > cid_ratio:
        return True
    if has_garbled_unicode(stripped):
        return True
    return False


class PdfParser(BaseParser):
    """混合策略：优先文本层提取，质量不合格再回退 OCR。"""

    def parse(self, content: bytes, filename: str, tmp_dir: str | None = None) -> list[RawElement]:
        suffix = os.path.splitext(filename)[1] or ".pdf"

        own_dir: str | None = None
        if tmp_dir is not None:
            work_dir = tmp_dir
        else:
            work_dir = tempfile.mkdtemp(prefix="fish-parse-")
            own_dir = work_dir

        path = os.path.join(work_dir, f"doc{suffix}")
        try:
            with open(path, "wb") as f:
                f.write(content)

            doc = fitz.open(path)
            try:
                text_layer = self._extract_text_layer(doc)
                if should_use_ocr(text_layer):
                    log.info("PDF text layer insufficient len=%s, fallback to OCR", len(text_layer.strip()))
                    return self._ocr_elements(doc)

                log.info("PDF text layer OK len=%s, use direct extraction", len(text_layer.strip()))
                return self._text_elements(doc)
            finally:
                doc.close()
        finally:
            try:
                os.remove(path)
            except OSError:
                pass
            if own_dir is not None:
                try:
                    os.rmdir(own_dir)
                except OSError:
                    pass

    @staticmethod
    def _extract_text_layer(doc: "fitz.Document") -> str:
        """拼接全部页的文本层，仅用于质量评估。"""
        return "\n".join(doc[page_idx].get_text("text") or "" for page_idx in range(len(doc)))

    @staticmethod
    def _text_elements(doc: "fitz.Document") -> list[RawElement]:
        """文字型 PDF：直接按非空行输出 Text 元素，清洗仍交给 processor 统一处理。"""
        out: list[RawElement] = []
        for page_idx in range(len(doc)):
            page_num = page_idx + 1
            page_text = doc[page_idx].get_text("text") or ""
            for line in page_text.split("\n"):
                line = line.strip()
                if line:
                    out.append(RawElement(text=line, page=page_num, element_type="Text"))
        log.info("text-layer extracted %s non-empty lines from %s pages", len(out), len(doc))
        return out

    @staticmethod
    def _ocr_elements(doc: "fitz.Document") -> list[RawElement]:
        """扫描件 PDF：保留原有 300 DPI 渲染 + Tesseract OCR 路径。"""
        out: list[RawElement] = []
        total_pages = len(doc)
        for page_idx in range(total_pages):
            page = doc[page_idx]
            page_num = page_idx + 1

            pix = page.get_pixmap(dpi=300)
            img_bytes = pix.tobytes("png")
            img = Image.open(io.BytesIO(img_bytes))
            page_text = pytesseract.image_to_string(img, lang="chi_sim+eng")

            if not page_text:
                continue
            for line in page_text.split("\n"):
                line = line.strip()
                if line:
                    out.append(RawElement(text=line, page=page_num, element_type="Text"))

        log.info("OCR extracted %s non-empty lines from %s pages (%s DPI)", len(out), total_pages, 300)
        return out
