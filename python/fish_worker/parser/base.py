# 解析器抽象基类 — 类似 Java 的 abstract class / interface
#
# @dataclass 是 Python 的数据类装饰器：
#   自动生成 __init__、__repr__、__eq__ 等方法
#   类比 Java 的 record (JDK 17+) 或 Lombok @Data
#
# ABC + @abstractmethod = Java 的 abstract class + abstract method
#   子类必须 override parse()，否则实例化时抛 TypeError
"""Abstract parser interface and data model."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass
class RawElement:
    """解析器产出的最小文本单元 —— 对应 PDF 中的一个段落/标题/表格等。

    page: None 表示无法确定页码（非 PDF 文件或 unstructured 未能提取）
    element_type: unstructured 的元素类型名，如 NarrativeText/Title/Table
    """
    text: str
    page: int | None
    element_type: str


class BaseParser(ABC):
    """解析器抽象基类。新格式（Word/HTML/Markdown）只需实现 parse() 并注册到 ParserFactory。"""

    @abstractmethod
    def parse(self, content: bytes, filename: str, tmp_dir: str | None = None) -> list[RawElement]:
        """Parse binary content into structured text elements.

        Args:
            content:  原始文件字节（从 MinIO 下载）。
            filename: 原始文件名（用于判断后缀，如 .pdf）。
            tmp_dir:  可选的临时目录。传入时 parser 在此目录写临时文件，
                      不传则 parser 自行创建（由 processor 传入以复用目录）。
        """
