# Embedding 向量化 — 调用 DashScope 或 Ollama HTTP API
#
# DashScope（阿里云灵积）：
#   - 批量 API 最多 25 条/次（FISH_WORKER_DASHSCOPE_EMBED_BATCH 控制）
#   - 返回向量按 text_index 排序
#   - 校验维度与 DASHSCOPE_EMBEDDING_DIMENSIONS 一致
#
# Ollama（本地部署）：
#   - 逐条调用 /api/embeddings（无批量 API）
#   - 维度可能不同于 DashScope → 仅 warning，不中断（需要确保 ES mapping 匹配）
#
# httpx 是 Python 的 HTTP 客户端（类似 Java 的 OkHttp / Spring RestClient）
# 比 requests 库更快（支持 HTTP/2），但不支持连接池的自动重连
"""Embedding via DashScope HTTP API or Ollama /api/embeddings."""

from __future__ import annotations

import logging
from typing import Any

import httpx

from fish_worker.config import Settings

log = logging.getLogger(__name__)


class Embedder:

    def __init__(self, settings: Settings) -> None:
        self._s = settings

    def embed_batch(self, texts: list[str]) -> list[list[float]]:
        """主入口：根据 FISH_LLM_EMBEDDING_PROVIDER 选择后端，批量向量化。

        Args:
            texts: 待 embedding 的文本列表（每行为一个 chunk 的 text）。

        Returns:
            相同顺序的向量列表 list[list[float]]，每个向量长度 = 配置的维度。
        """
        if not texts:
            return []
        provider = self._s.fish_llm_embedding_provider
        if provider == "DASHSCOPE":
            return self._dashscope_embed(texts)
        if provider == "OLLAMA":
            return self._ollama_embed(texts)
        raise ValueError(f"Unknown embedding provider: {provider}")

    # ----------- DashScope -----------

    def _dashscope_embed(self, texts: list[str]) -> list[list[float]]:
        """DashScope 批量 embedding（text-embedding-v2 / v3 等）。"""
        key = self._s.dashscope_api_key
        if not key:
            raise RuntimeError("DASHSCOPE_API_KEY is required for DashScope embeddings")

        # DashScope text-embedding API 端点
        url = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding"
        batch_max = self._s.fish_worker_dashscope_embed_batch
        dims_expected = self._s.dashscope_embedding_dimensions

        all_vecs: list[list[float]] = []

        # httpx.Client 类比 Java 的 OkHttpClient，可用 with 管理生命周期
        with httpx.Client(timeout=120.0) as client:
            for i in range(0, len(texts), batch_max):
                batch = texts[i : i + batch_max]
                body = {
                    "model": self._s.dashscope_embedding_model,
                    "input": {"texts": batch},
                }
                r = client.post(
                    url,
                    headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
                    json=body,
                )
                r.raise_for_status()  # HTTP 4xx/5xx → 抛异常
                data = r.json()
                emb_block = self._parse_dashscope_output(data, len(batch))

                # 维度校验（确保与 ES dense_vector mapping 一致）
                for vec in emb_block:
                    if len(vec) != dims_expected:
                        raise RuntimeError(
                            f"Embedding dimension mismatch: got {len(vec)}, expected {dims_expected}"
                        )
                all_vecs.extend(emb_block)

        return all_vecs

    @staticmethod
    def _parse_dashscope_output(data: dict[str, Any], batch_len: int) -> list[list[float]]:
        """解析 DashScope embedding API 响应。

        响应格式示例：
        {"output": {"embeddings": [{"text_index": 0, "embedding": [...]}, ...]}}
        或单条模式：
        {"output": {"embedding": [...]}}
        """
        if data.get("code"):
            raise RuntimeError(f"DashScope API error: {data!r}")

        output = data.get("output") or {}
        embeddings = output.get("embeddings") or []

        # 批量返回：按 text_index 排序（异步返回顺序可能不按输入顺序）
        if isinstance(embeddings, list) and embeddings:
            items = [x for x in embeddings if isinstance(x, dict)]
            items.sort(key=lambda x: int(x.get("text_index", 0)))
            vecs = []
            for item in items:
                vec = item.get("embedding")
                if not isinstance(vec, list):
                    continue
                vecs.append([float(x) for x in vec])
            if len(vecs) == batch_len:
                return vecs

        # 单条返回（batch_len=1 时会走这个分支）
        alt = output.get("embedding")
        if isinstance(alt, list) and batch_len == 1:
            return [[float(x) for x in alt]]

        raise RuntimeError(f"Unexpected DashScope embedding response: {data!r}")

    # ----------- Ollama -----------

    def _ollama_embed(self, texts: list[str]) -> list[list[float]]:
        """Ollama 本地 embedding（逐条调用，无批量 API）。"""
        base = self._s.ollama_base_url.rstrip("/")
        model = self._s.ollama_embedding_model
        # Ollama 模型维度可能与 DashScope 不同，仅用此值做参考对比
        dims_expected = self._s.dashscope_embedding_dimensions

        vecs: list[list[float]] = []
        with httpx.Client(timeout=120.0) as client:
            for t in texts:
                r = client.post(f"{base}/api/embeddings", json={"model": model, "prompt": t})
                r.raise_for_status()
                data = r.json()
                emb = data.get("embedding")
                if not isinstance(emb, list):
                    raise RuntimeError(f"Unexpected Ollama embedding response: {data!r}")
                vec = [float(x) for x in emb]
                # 维度不一致只警告（Ollama 自定义模型维度不可控）
                if len(vec) != dims_expected:
                    log.warning(
                        "Ollama embedding dims=%s differ from DASHSCOPE_EMBEDDING_DIMENSIONS=%s; "
                        "ensure ES dense_vector mapping matches your model",
                        len(vec),
                        dims_expected,
                    )
                vecs.append(vec)
        return vecs
