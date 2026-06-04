"""HTML parser that extracts visible semantic text."""

from __future__ import annotations

from fish_worker.parser.base import BaseParser, RawElement
from fish_worker.parser.encoding import decode_bytes

_BLOCK_TAGS = ["p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "td", "th", "blockquote"]


class HtmlParser(BaseParser):
    """Extract visible text from common block-level HTML tags."""

    def parse(self, content: bytes, filename: str, tmp_dir: str | None = None) -> list[RawElement]:
        from bs4 import BeautifulSoup

        soup = BeautifulSoup(self._decode_html(content), "lxml")
        for tag in soup(["script", "style", "noscript"]):
            tag.decompose()

        elements: list[RawElement] = []
        for tag in soup.find_all(_BLOCK_TAGS):
            text = tag.get_text(strip=True)
            if not text:
                continue
            element_type = "Title" if tag.name.startswith("h") else "Text"
            elements.append(RawElement(text=text, page=None, element_type=element_type))

        if not elements:
            elements = [
                RawElement(text=line, page=None, element_type="Text")
                for raw_line in soup.get_text(separator="\n").splitlines()
                if (line := raw_line.strip())
            ]

        return self._dedupe(elements)

    @staticmethod
    def _decode_html(content: bytes) -> str:
        from bs4 import BeautifulSoup

        probe = BeautifulSoup(content.decode("latin-1"), "lxml")
        meta = probe.find("meta", attrs={"charset": True})
        charset = meta.get("charset") if meta else None
        return decode_bytes(content, extra=[charset] if charset else None)

    @staticmethod
    def _dedupe(elements: list[RawElement]) -> list[RawElement]:
        # Nested semantic tags can surface the same visible text more than once.
        seen: set[str] = set()
        unique: list[RawElement] = []
        for elem in elements:
            if elem.text in seen:
                continue
            seen.add(elem.text)
            unique.append(elem)
        return unique
