# 文本分块 & 向量嵌入
"""Text chunking and embedding."""

from fish_worker.chunker.embedder import Embedder
from fish_worker.chunker.text_chunker import TextChunk, chunk_elements

__all__ = ["Embedder", "TextChunk", "chunk_elements"]
