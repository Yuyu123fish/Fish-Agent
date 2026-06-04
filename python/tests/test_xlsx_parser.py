from __future__ import annotations

import io
import unittest

from openpyxl import Workbook

from fish_worker.parser.xlsx_parser import XlsxParser


def make_xlsx_bytes() -> bytes:
    wb = Workbook()
    ws = wb.active
    ws.title = "Sheet1"
    ws.append(["姓名", "分数"])
    ws.append(["小鱼", 98])
    ws.append([None, None])

    out = io.BytesIO()
    wb.save(out)
    wb.close()
    return out.getvalue()


class XlsxParserTest(unittest.TestCase):
    def test_extracts_non_empty_rows_as_table_elements(self) -> None:
        elements = XlsxParser().parse(make_xlsx_bytes(), "scores.xlsx")

        self.assertEqual(["姓名 | 分数", "小鱼 | 98"], [e.text for e in elements])
        self.assertTrue(all(e.page is None for e in elements))
        self.assertTrue(all(e.element_type == "Table" for e in elements))


if __name__ == "__main__":
    unittest.main()
