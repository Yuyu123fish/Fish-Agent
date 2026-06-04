from __future__ import annotations

import unittest

from fish_worker.parser.text_parser import TextParser


class TextParserTest(unittest.TestCase):
    def test_decodes_utf8_bom_and_splits_non_empty_lines(self) -> None:
        parser = TextParser()

        elements = parser.parse("\ufeff第一行\n\n 第二行 ".encode("utf-8-sig"), "note.txt")

        self.assertEqual(["第一行", "第二行"], [e.text for e in elements])
        self.assertTrue(all(e.page is None for e in elements))
        self.assertTrue(all(e.element_type == "Text" for e in elements))

    def test_decodes_gbk_chinese_text(self) -> None:
        parser = TextParser()

        elements = parser.parse("中文测试\n第二行".encode("gbk"), "note.txt")

        self.assertEqual(["中文测试", "第二行"], [e.text for e in elements])

    def test_empty_content_returns_no_elements(self) -> None:
        self.assertEqual([], TextParser().parse(b"", "empty.md"))


if __name__ == "__main__":
    unittest.main()
