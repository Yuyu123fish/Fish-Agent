from __future__ import annotations

import unittest

from fish_worker.chunker.sentence_splitter import split_sentences
from fish_worker.chunker.structured_chunker import chunk_elements
from fish_worker.chunker.text_chunker import count_tokens
from fish_worker.parser.base import RawElement


class SentenceSplitterTest(unittest.TestCase):
    def test_splits_on_chinese_enders(self) -> None:
        self.assertEqual(
            ["第一句。", "第二句？", "第三句！"],
            split_sentences("第一句。第二句？第三句！"),
        )

    def test_keeps_closing_quote_with_sentence(self) -> None:
        self.assertEqual(
            ['他说：“你好。”', "然后离开了。"],
            split_sentences('他说：“你好。”然后离开了。'),
        )

    def test_splits_on_english_enders(self) -> None:
        self.assertEqual(
            ["Hello world.", "Bye!"],
            split_sentences("Hello world. Bye!"),
        )

    def test_does_not_split_decimal_point(self) -> None:
        self.assertEqual(
            ["圆周率约为3.14的值。"],
            split_sentences("圆周率约为3.14的值。"),
        )

    def test_drops_empty_and_keeps_tail_without_ender(self) -> None:
        self.assertEqual(["只有一段没有标点的文本"], split_sentences("只有一段没有标点的文本"))
        self.assertEqual([], split_sentences("   "))


class StructuredChunkerTest(unittest.TestCase):
    def test_title_merges_with_following_text(self) -> None:
        elements = [
            RawElement(text="Redis 持久化", page=1, element_type="Title"),
            RawElement(text="支持 RDB 快照。", page=1, element_type="Text"),
            RawElement(text="也支持 AOF 日志。", page=1, element_type="Text"),
        ]

        chunks = chunk_elements(elements, chunk_size=512, overlap=50, table_max_tokens=1024)

        self.assertEqual(1, len(chunks))
        self.assertIn("Redis 持久化", chunks[0].text)
        self.assertIn("RDB", chunks[0].text)
        self.assertNotEqual("Redis 持久化", chunks[0].text)

    def test_small_table_kept_whole(self) -> None:
        elements = [
            RawElement(text="名称 | 数值", page=None, element_type="Table"),
            RawElement(text="QPS | 1000", page=None, element_type="Table"),
            RawElement(text="延迟 | 5ms", page=None, element_type="Table"),
        ]

        chunks = chunk_elements(elements, chunk_size=512, overlap=50, table_max_tokens=1024)

        self.assertEqual(1, len(chunks))
        self.assertIn("名称 | 数值", chunks[0].text)
        self.assertIn("延迟 | 5ms", chunks[0].text)

    def test_large_table_splits_into_row_groups_repeating_header(self) -> None:
        rows = [RawElement(text="col_a | col_b", page=None, element_type="Table")]
        for i in range(8):
            rows.append(RawElement(text=f"v{i}a | v{i}b", page=None, element_type="Table"))

        chunks = chunk_elements(rows, chunk_size=12, overlap=0, table_max_tokens=12)

        self.assertGreater(len(chunks), 1)
        for chunk in chunks:
            self.assertTrue(chunk.text.startswith("col_a | col_b"))

    def test_plain_text_not_cut_mid_sentence(self) -> None:
        text = "第一句话内容比较长一些。第二句话也有一定长度。第三句话同样不短。"
        elements = [RawElement(text=text, page=1, element_type="Text")]
        one_sentence_tokens = count_tokens("第一句话内容比较长一些。")

        chunks = chunk_elements(elements, chunk_size=one_sentence_tokens + 2, overlap=0, table_max_tokens=1024)

        self.assertGreater(len(chunks), 1)
        for chunk in chunks:
            self.assertTrue(chunk.text.endswith("。"), f"chunk 在句中被硬切: {chunk.text!r}")

    def test_oversized_single_sentence_falls_back_to_token_window(self) -> None:
        elements = [RawElement(text="甲" * 300, page=1, element_type="Text")]

        chunks = chunk_elements(elements, chunk_size=50, overlap=10, table_max_tokens=1024)

        self.assertGreater(len(chunks), 1)
        for chunk in chunks:
            self.assertLessEqual(chunk.token_count, 50)

    def test_global_chunk_index_is_sequential(self) -> None:
        elements = [
            RawElement(text="标题", page=1, element_type="Title"),
            RawElement(text="正文。", page=1, element_type="Text"),
            RawElement(text="a | b", page=2, element_type="Table"),
        ]

        chunks = chunk_elements(elements, chunk_size=512, overlap=50, table_max_tokens=1024)

        self.assertEqual(list(range(len(chunks))), [chunk.chunk_index for chunk in chunks])

    def test_empty_returns_empty(self) -> None:
        self.assertEqual([], chunk_elements([], chunk_size=512, overlap=50, table_max_tokens=1024))


if __name__ == "__main__":
    unittest.main()
