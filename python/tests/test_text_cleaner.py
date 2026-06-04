from __future__ import annotations

import unittest

from fish_worker.parser.base import RawElement
from fish_worker.parser.text_cleaner import (
    clean_elements,
    clean_ocr_text,
    collapse_punctuation,
    fix_common_ocr_errors,
    normalize_unicode,
    normalize_whitespace,
    remove_control_chars,
)


class TextCleanerTest(unittest.TestCase):
    def test_normalizes_combining_unicode(self) -> None:
        self.assertEqual("é", normalize_unicode("e\u0301"))

    def test_removes_control_and_zero_width_chars_but_keeps_text_whitespace(self) -> None:
        dirty = "a\x00b\x07c\x1f\u200b\ufeff\n\t\rd"

        self.assertEqual("abc\n\t\rd", remove_control_chars(dirty))

    def test_fixes_common_ocr_ligatures_and_cid_artifacts(self) -> None:
        dirty = "ﬁle ﬂow ﬀoo ﬃce ﬄag abc(cid:1339)def"

        self.assertEqual("file flow ffoo ffice fflag abcdef", fix_common_ocr_errors(dirty))

    def test_collapses_repeated_punctuation(self) -> None:
        dirty = "好。。。啊，，，嗯；；！！？？ wait...."

        self.assertEqual("好。啊，嗯；！？ wait…", collapse_punctuation(dirty))

    def test_normalizes_spaces_without_destroying_paragraph_boundaries(self) -> None:
        dirty = " a    b\t c \n  line2\n\n\n\npara2 "

        self.assertEqual("a b c\nline2\n\npara2", normalize_whitespace(dirty))

    def test_clean_ocr_text_runs_the_full_pipeline(self) -> None:
        dirty = "\ufeffﬁle(cid:42)  \x00  好。。。 \n\n\n second\tline"

        self.assertEqual("file 好。\n\nsecond line", clean_ocr_text(dirty))

    def test_clean_elements_filters_empty_text_and_preserves_metadata(self) -> None:
        elements = [
            RawElement(text="\x00\u200b", page=1, element_type="Text"),
            RawElement(text="ﬁle。。。", page=2, element_type="Title"),
        ]

        cleaned = clean_elements(elements)

        self.assertEqual([RawElement(text="file。", page=2, element_type="Title")], cleaned)


if __name__ == "__main__":
    unittest.main()
