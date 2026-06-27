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
from datetime import datetime, timedelta, timezone

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
_NOISY_SCRIPT_RATIO_THRESHOLD = 0.12
_CID_PATTERN = re.compile(r"\(cid:\d+\)")

# PDF 日期串（PDF 1.7 §7.9.4）：D:YYYYMMDDHHmmSS 后跟可选偏移——Z、+HH'mm'、-HH'mm'，或无偏移。
# 例：D:20260101120000Z / D:20260101120000+08'00' / D:20250615103000（无偏移，视为 UTC）
# 末尾 $ 拒绝尾随垃圾（如 D:20250615103000Z00）。
_PDF_DATE_PATTERN = re.compile(
    r"D:(?P<y>\d{4})(?P<mo>\d{2})(?P<d>\d{2})(?P<H>\d{2})(?P<mi>\d{2})(?P<s>\d{2})"
    r"(?:Z|(?P<sign>[+\-])(?P<ohh>\d{2})?'?(?P<omm>\d{2})?'?)?$"
)


def parse_pdf_date(text: str | None) -> datetime | None:
    """解析 PDF 元数据日期串 D:YYYYMMDDHHmmSS[OHH'mm'] → aware datetime；非法/空返回 None。

    无偏移时按 PDF 规范视为 UTC（与 PyMuPDF 默认一致）。
    """
    if not text:
        return None
    m = _PDF_DATE_PATTERN.match(text.strip())
    if not m:
        return None
    if m.group("sign") is not None:
        sign = 1 if m.group("sign") == "+" else -1
        ohh = int(m.group("ohh") or 0)
        omm = int(m.group("omm") or 0)
        tz = timezone(sign * timedelta(hours=ohh, minutes=omm))
    else:
        # Z 或无偏移 → UTC
        tz = timezone.utc
    try:
        return datetime(
            int(m.group("y")), int(m.group("mo")), int(m.group("d")),
            int(m.group("H")), int(m.group("mi")), int(m.group("s")),
            tzinfo=tz,
        )
    except ValueError:
        return None


def count_cid_residuals(text: str) -> int:
    """统计 (cid:N) 残留字符数，这是 PDF 字体映射损坏的典型信号。"""
    return sum(len(match.group(0)) for match in _CID_PATTERN.finditer(text or ""))


def has_garbled_unicode(text: str, threshold: float = _GARBLED_RATIO_THRESHOLD) -> bool:
    """检测替换符和私用区字符占比，超过阈值说明文本层质量较差。"""
    if not text:
        return False
    bad = sum(1 for ch in text if ch == "\ufffd" or "\ue000" <= ch <= "\uf8ff")
    return bad / len(text) > threshold


def _is_common_script_char(ch: str) -> bool:
    """判断字符是否属于中文/英文文档中常见的 Unicode 区段。

    不在此范围内的字符（泰文、缅甸文、阿拉伯文、埃塞俄比亚文等冷门文字）
    如果大量出现，几乎可以确定是 PDF 字体 CMap 映射错误导致的乱码。
    """
    cp = ord(ch)
    # Basic ASCII（英文字母、数字、常用标点）
    if cp <= 0x007F:
        return True
    # Latin-1 Supplement（部分西欧字符，如 é ñ）
    if 0x00A0 <= cp <= 0x00FF:
        return True
    # Latin Extended（常见于学术论文的人名、术语）
    if 0x0100 <= cp <= 0x024F:
        return True
    # 通用标点（em dash、引号、省略号等）
    if 0x2000 <= cp <= 0x206F:
        return True
    # 上标/下标、货币符号、箭头、数学符号、杂项技术符号
    if 0x2070 <= cp <= 0x20CF:
        return True
    # CJK 标点符号（「」、。，、；：等）
    if 0x3000 <= cp <= 0x303F:
        return True
    # 日文平假名 / 片假名（中日混排文档常见）
    if 0x3040 <= cp <= 0x30FF:
        return True
    # CJK Unified Ideographs 及扩展区（中日韩汉字）
    if 0x3400 <= cp <= 0x4DBF:
        return True
    if 0x4E00 <= cp <= 0x9FFF:
        return True
    if 0xF900 <= cp <= 0xFAFF:
        return True
    # CJK Compatibility Forms（如 ︰﹏）
    if 0xFE30 <= cp <= 0xFE4F:
        return True
    # 半角/全角形式（！＂＃￥等）
    if 0xFF00 <= cp <= 0xFFEF:
        return True
    # CJK Extension B-I（罕见汉字，学术论文偶尔出现）
    if 0x20000 <= cp <= 0x2FA1F:
        return True
    return False


def has_noisy_scripts(text: str, threshold: float = _NOISY_SCRIPT_RATIO_THRESHOLD) -> bool:
    """检测文本中是否混入大量非常见文字区段字符。

    PDF 字体 CMap 损坏时，PyMuPDF 会把错误的 glyph ID 映射到泰文、缅甸文、
    阿拉伯文、埃塞俄比亚文等冷门 Unicode 区段，产出的文本看起来像各种语言的
    字符随机拼凑。这种乱码不包含 (cid:N) 和 U+FFFD，因此原有的两个检查
    无法捕获；但通过统计"非常见区段"字符占比可以有效识别。
    """
    if not text:
        return False
    total = 0
    noisy = 0
    for ch in text:
        # 跳过空白字符（空格、换行、制表符），它们不属于任何文字区段
        if ch in (" ", "\n", "\r", "\t"):
            continue
        total += 1
        if not _is_common_script_char(ch):
            noisy += 1
    if total == 0:
        return False
    ratio = noisy / total
    if ratio > threshold:
        log.info(
            "noisy script detected: ratio=%.3f noisy=%d total=%d sample=%r",
            ratio, noisy, total, text[:80],
        )
        return True
    return False


def should_use_ocr(
    text: str,
    *,
    min_chars: int = _MIN_TEXT_CHARS,
    cid_ratio: float = _CID_RATIO_THRESHOLD,
    noisy_ratio: float = _NOISY_SCRIPT_RATIO_THRESHOLD,
) -> bool:
    """判断 PDF 文本层是否需要回退 OCR。阈值偏保守，宁可多 OCR 也不写入乱码。"""
    stripped = (text or "").strip()
    if len(stripped) < min_chars:
        return True
    if count_cid_residuals(stripped) / max(1, len(stripped)) > cid_ratio:
        return True
    if has_garbled_unicode(stripped):
        return True
    if has_noisy_scripts(stripped, threshold=noisy_ratio):
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

    def created_at(self, content: bytes, filename: str) -> datetime | None:
        """从 PDF 元数据取真实创建时间（fitz.metadata['creation']）。"""
        try:
            doc = fitz.open(stream=content, filetype="pdf")
            try:
                return parse_pdf_date(doc.metadata.get("creation"))
            finally:
                doc.close()
        except Exception:
            return None

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
