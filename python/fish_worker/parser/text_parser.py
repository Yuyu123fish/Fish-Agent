"""Plain text and Markdown parser."""

from __future__ import annotations

from fish_worker.parser.base import BaseParser, RawElement
from fish_worker.parser.encoding import decode_bytes


class TextParser(BaseParser):
    """Parse text-like files into one element per non-empty line."""

    def parse(self, content: bytes, filename: str, tmp_dir: str | None = None) -> list[RawElement]:
        text = decode_bytes(content)
        return [
            RawElement(text=line, page=None, element_type="Text")
            for raw_line in text.splitlines()
            if (line := raw_line.strip())
        ]
