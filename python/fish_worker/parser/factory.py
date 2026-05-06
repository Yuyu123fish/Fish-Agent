# 解析器工厂 — 根据 MIME 类型分派具体的 Parser 实现
#
# 类比 Java 的工厂模式（Factory Pattern）
# 这里用类方法 + 类级别字典做注册表，无需 Spring 的 @Autowired Map<String, Parser>
#
# 关键设计：MIME 来自 MinIO GetObject 响应头（Java 上传时设置），不从文件名猜测后缀
# 容错逻辑：如果 MIME 是 application/octet-stream 但文件名以 .pdf 结尾，推断为 PDF
#           （某些网关/代理会丢失 Content-Type）
"""Maps MIME type → parser implementation."""

from __future__ import annotations

import logging

from fish_worker.exceptions import UnsupportedFileTypeError
from fish_worker.parser.base import BaseParser
from fish_worker.parser.pdf import PdfParser

log = logging.getLogger(__name__)


class ParserFactory:
    # 注册表：MIME 类型 → Parser 类（不是实例，每次 get() 新建实例）
    # 新增文件格式只需在这里加一行
    _registry: dict[str, type[BaseParser]] = {
        "application/pdf": PdfParser,
        # 预留： "application/vnd.openxmlformats-officedocument.wordprocessingml.document": WordParser,
        # 预留： "text/html": HtmlParser,
    }

    @classmethod
    def normalize_content_type(cls, content_type: str) -> str:
        """标准化 MIME：去掉 charset 参数，统一小写。

        'application/pdf; charset=utf-8' → 'application/pdf'
        """
        return content_type.split(";")[0].strip().lower()

    @classmethod
    def get(cls, content_type: str, filename: str = "") -> BaseParser:
        """根据 MIME 类型返回对应的 Parser 实例。

        Args:
            content_type: MinIO 响应头的 Content-Type（首选）。
            filename:     仅当 content_type 为 octet-stream 时用于后缀推断。
        """
        ct = cls.normalize_content_type(content_type)
        klass = cls._registry.get(ct)

        # 容错：网关丢失 MIME → 从文件名后缀推断
        if klass is None and ct == "application/octet-stream":
            fn = (filename or "").lower()
            if fn.endswith(".pdf"):
                log.warning("content-type octet-stream; inferring PDF from filename=%s", filename)
                klass = PdfParser

        if klass is None:
            raise UnsupportedFileTypeError(f"unsupported content-type: {content_type!r}")

        # 每次调用 new 一个新实例（Parser 无状态，开销可忽略）
        return klass()
