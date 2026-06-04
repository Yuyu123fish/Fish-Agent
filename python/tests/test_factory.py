from __future__ import annotations

import importlib
import unittest
from unittest.mock import patch

from fish_worker.exceptions import UnsupportedFileTypeError
from fish_worker.parser.docx_parser import DocxParser
from fish_worker.parser import factory as factory_module
from fish_worker.parser.html_parser import HtmlParser
from fish_worker.parser.pdf import PdfParser
from fish_worker.parser.pptx_parser import PptxParser
from fish_worker.parser.text_parser import TextParser
from fish_worker.parser.xlsx_parser import XlsxParser

ParserFactory = factory_module.ParserFactory


class ParserFactoryTest(unittest.TestCase):
    def test_dispatches_registered_mime_types(self) -> None:
        cases = [
            ("application/pdf", "a.pdf", PdfParser),
            ("text/plain; charset=utf-8", "a.txt", TextParser),
            ("text/markdown", "a.md", TextParser),
            ("text/x-markdown", "a.markdown", TextParser),
            (
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "a.docx",
                DocxParser,
            ),
            ("text/html; charset=gbk", "a.html", HtmlParser),
            (
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "a.xlsx",
                XlsxParser,
            ),
            (
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "a.pptx",
                PptxParser,
            ),
        ]

        for content_type, filename, expected_type in cases:
            with self.subTest(content_type=content_type):
                self.assertIsInstance(ParserFactory.get(content_type, filename), expected_type)

    def test_infers_octet_stream_from_filename_suffix(self) -> None:
        cases = [
            ("a.pdf", PdfParser),
            ("a.txt", TextParser),
            ("a.log", TextParser),
            ("a.md", TextParser),
            ("a.markdown", TextParser),
            ("a.docx", DocxParser),
            ("a.html", HtmlParser),
            ("a.htm", HtmlParser),
            ("a.xlsx", XlsxParser),
            ("a.pptx", PptxParser),
        ]

        for filename, expected_type in cases:
            with self.subTest(filename=filename):
                self.assertIsInstance(ParserFactory.get("application/octet-stream", filename), expected_type)

    def test_unsupported_type_raises(self) -> None:
        with self.assertRaises(UnsupportedFileTypeError):
            ParserFactory.get("application/x-rar", "archive.rar")

    def test_optional_office_parsers_are_skipped_when_modules_are_missing(self) -> None:
        original_import_module = importlib.import_module
        missing_modules = {
            "fish_worker.parser.xlsx_parser",
            "fish_worker.parser.pptx_parser",
        }

        def guarded_import_module(name, *args, **kwargs):
            if name in missing_modules:
                raise ImportError(f"missing optional module: {name}")
            return original_import_module(name, *args, **kwargs)

        try:
            with patch("importlib.import_module", side_effect=guarded_import_module):
                reloaded = importlib.reload(factory_module)

            self.assertNotIn(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                reloaded.ParserFactory._registry,
            )
            self.assertNotIn(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                reloaded.ParserFactory._registry,
            )
            with self.assertRaises(UnsupportedFileTypeError):
                reloaded.ParserFactory.get("application/octet-stream", "demo.xlsx")
            with self.assertRaises(UnsupportedFileTypeError):
                reloaded.ParserFactory.get("application/octet-stream", "demo.pptx")
        finally:
            importlib.reload(factory_module)

        self.assertIsInstance(
            factory_module.ParserFactory.get(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "demo.xlsx",
            ),
            XlsxParser,
        )
        self.assertIsInstance(
            factory_module.ParserFactory.get(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "demo.pptx",
            ),
            PptxParser,
        )


if __name__ == "__main__":
    unittest.main()
