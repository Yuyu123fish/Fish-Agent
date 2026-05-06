# 文档解析器 — 把二进制文件解析为结构化文本元素
#
# 扩展方式：实现 BaseParser，在 ParserFactory._registry 注册 MIME → Parser 映射
"""Document parsers (PDF via unstructured, extensible factory)."""

from fish_worker.parser.base import BaseParser, RawElement
from fish_worker.parser.factory import ParserFactory

__all__ = ["BaseParser", "RawElement", "ParserFactory"]
