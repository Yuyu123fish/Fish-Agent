from __future__ import annotations

import unittest

from fish_worker.parser.html_parser import HtmlParser


class HtmlParserTest(unittest.TestCase):
    def test_extracts_semantic_text_and_ignores_script_style(self) -> None:
        html = """
        <html>
          <head><style>.hidden { color: red; }</style></head>
          <body>
            <h1>标题</h1>
            <p>正文</p>
            <ul><li>列表项</li></ul>
            <script>alert('bad')</script>
          </body>
        </html>
        """.encode("utf-8")

        elements = HtmlParser().parse(html, "page.html")

        self.assertEqual(
            [("标题", "Title"), ("正文", "Text"), ("列表项", "Text")],
            [(e.text, e.element_type) for e in elements],
        )
        joined = "\n".join(e.text for e in elements)
        self.assertNotIn("alert", joined)
        self.assertNotIn("hidden", joined)

    def test_decodes_gbk_meta_charset(self) -> None:
        html = '<html><head><meta charset="gbk"></head><body><p>中文正文</p></body></html>'

        elements = HtmlParser().parse(html.encode("gbk"), "page.html")

        self.assertEqual(["中文正文"], [e.text for e in elements])

    def test_falls_back_to_whole_text_when_no_semantic_tags(self) -> None:
        elements = HtmlParser().parse(b"<html><body>only text<br>second</body></html>", "page.html")

        self.assertEqual(["only text", "second"], [e.text for e in elements])


if __name__ == "__main__":
    unittest.main()
