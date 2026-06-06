from __future__ import annotations

import unittest
from unittest.mock import patch

import fitz

from fish_worker.parser.pdf import PdfParser, count_cid_residuals, has_garbled_unicode, should_use_ocr


def _text_pdf(text: str) -> bytes:
    doc = fitz.open()
    page = doc.new_page()
    page.insert_text((72, 72), text)
    data = doc.tobytes()
    doc.close()
    return data


def _blank_pdf() -> bytes:
    doc = fitz.open()
    doc.new_page()
    data = doc.tobytes()
    doc.close()
    return data


class PdfQualityFunctionsTest(unittest.TestCase):
    def test_count_cid_residuals_counts_matched_chars(self) -> None:
        self.assertEqual(0, count_cid_residuals("clean text"))
        self.assertEqual(len("(cid:1339)"), count_cid_residuals("ab(cid:1339)cd"))

    def test_has_garbled_unicode(self) -> None:
        self.assertFalse(has_garbled_unicode("正常的中文文本 normal english"))
        self.assertTrue(has_garbled_unicode("\ufffd\ufffd\ufffd\ufffd\ufffdok"))

    def test_should_use_ocr_for_short_text(self) -> None:
        self.assertTrue(should_use_ocr("太短"))

    def test_should_not_use_ocr_for_clean_long_text(self) -> None:
        clean = "Redis persistence supports RDB snapshots and AOF append-only logging for durability."
        self.assertFalse(should_use_ocr(clean))

    def test_should_use_ocr_for_cid_heavy_text(self) -> None:
        dirty = "(cid:1)(cid:2)(cid:3)(cid:4)(cid:5)(cid:6)(cid:7)abc"
        self.assertTrue(should_use_ocr(dirty))


class PdfHybridParseTest(unittest.TestCase):
    def test_text_layer_used_without_ocr(self) -> None:
        clean = "Redis persistence supports RDB snapshots and AOF append-only logging for durability."
        content = _text_pdf(clean)
        with patch(
            "fish_worker.parser.pdf.pytesseract.image_to_string",
            side_effect=AssertionError("OCR should not be called"),
        ):
            elements = PdfParser().parse(content, "doc.pdf")

        self.assertTrue(elements)
        joined = " ".join(element.text for element in elements)
        self.assertIn("Redis", joined)
        self.assertTrue(all(element.element_type == "Text" for element in elements))

    def test_falls_back_to_ocr_for_blank_text_layer(self) -> None:
        content = _blank_pdf()
        with patch(
            "fish_worker.parser.pdf.pytesseract.image_to_string",
            return_value="scanned line one\nscanned line two",
        ) as ocr:
            elements = PdfParser().parse(content, "scan.pdf")

        ocr.assert_called()
        joined = " ".join(element.text for element in elements)
        self.assertIn("scanned line one", joined)


if __name__ == "__main__":
    unittest.main()
