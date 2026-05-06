# PDF 解析器 — PyMuPDF 渲染为图片 → pytesseract OCR 识别
#
# 为什么用 OCR 而不是直接提取文本：
#   三个库 (pypdf / pdfminer / MuPDF 文本提取) 都失败了：
#     unstructured+pypdf → 中文乱码
#     pdfplumber+pdfminer → (cid:1339) CID 原始编号
#     PyMuPDF get_text() → Իഘഭଧ 各种文字混搭
#   根因是某些中文 PDF 的字体子集化时 CMap 映射表缺失/损坏，
#   所有基于字体解码的方案都绕不开这个问题。
#
# OCR 流程（完全不碰字体编码）：
#   PDF → PyMuPDF 每页渲染为 300 DPI PNG → pytesseract (chi_sim+eng) → 文本行
#   这是"看见画面 → 认出文字"的过程，和人类阅读 PDF 一样。
#
# 性能：每页 1-3 秒（文本提取是毫秒级），异步 Worker 可接受。
"""PDF parsing via PyMuPDF render → pytesseract OCR (chi_sim+eng)."""

from __future__ import annotations

import io
import logging
import os
import tempfile

import fitz  # PyMuPDF
import pytesseract
from PIL import Image

# Windows 上 Tesseract 可能不在系统 PATH 中，尝试常见安装路径
import os as _os
_possible_paths = [
    r"D:\develope\Tesseract-OCR\tesseract.exe",
    r"C:\Program Files\Tesseract-OCR\tesseract.exe",
]
for _p in _possible_paths:
    if _os.path.exists(_p):
        pytesseract.pytesseract.tesseract_cmd = _p
        break

from fish_worker.parser.base import BaseParser, RawElement

log = logging.getLogger(__name__)


class PdfParser(BaseParser):

    def parse(self, content: bytes, filename: str, tmp_dir: str | None = None) -> list[RawElement]:
        """OCR 解析 PDF：逐页渲染为图片 → Tesseract 识别文字。

        语言配置：chi_sim（简体中文）+ eng（英文），覆盖中英混排文档。
        DPI=300 是 OCR 的推荐值（太低识别率下降，太高速度慢收益小）。
        """
        suffix = os.path.splitext(filename)[1] or ".pdf"

        own_dir: str | None = None
        work_dir: str
        if tmp_dir is not None:
            work_dir = tmp_dir
        else:
            work_dir = tempfile.mkdtemp(prefix="fish-parse-")
            own_dir = work_dir

        path = os.path.join(work_dir, f"doc{suffix}")

        try:
            with open(path, "wb") as f:
                f.write(content)

            out: list[RawElement] = []

            doc = fitz.open(path)
            total_pages = len(doc)

            try:
                for page_idx in range(total_pages):
                    page = doc[page_idx]
                    page_num = page_idx + 1

                    # 第 1 步：渲染页面为 300 DPI 的 PNG 图片
                    # get_pixmap() 把 PDF 页面光栅化为像素矩阵
                    pix = page.get_pixmap(dpi=300)

                    # 第 2 步：pixmap → PNG bytes → PIL Image
                    img_bytes = pix.tobytes("png")
                    img = Image.open(io.BytesIO(img_bytes))

                    # 第 3 步：Tesseract OCR 识别
                    # lang='chi_sim+eng' 同时启用简体中文和英文识别
                    page_text = pytesseract.image_to_string(img, lang="chi_sim+eng")

                    if not page_text:
                        continue

                    for line in page_text.split("\n"):
                        line = line.strip()
                        if line:
                            out.append(
                                RawElement(
                                    text=line,
                                    page=page_num,
                                    element_type="Text",
                                )
                            )
            finally:
                doc.close()

            log.info("OCR extracted %s non-empty lines from %s pages (%s DPI)",
                     len(out), total_pages, 300)
            return out

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
