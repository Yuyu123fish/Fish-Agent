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
import random
import time
from typing import Any

import httpx

from fish_worker.config import Settings

log = logging.getLogger(__name__)

_RETRYABLE_STATUS = {429, 500, 502, 503, 504}


class _RetryableHttpStatus(Exception):
    """标记 HTTP 层可重试状态码，保留原响应用于最终抛出标准异常。"""

    def __init__(self, response: httpx.Response) -> None:
        self.response = response
        super().__init__(f"retryable HTTP status: {response.status_code}")


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
                r = self._post_with_retry(
                    client,
                    url,
                    headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
                    json=body,
                )
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
                r = self._post_with_retry(
                    client,
                    f"{base}/api/embeddings",
                    json={"model": model, "prompt": t},
                )
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

    # ----------- HTTP retry -----------

    def _post_with_retry(
        self,
        client: httpx.Client,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        json: dict[str, Any] | None = None,
    ) -> httpx.Response:
        """执行可重试 POST。

        只重试短暂性错误：HTTP 429 / 5xx 与网络异常。HTTP 400/401/403 等配置或请求错误
        立即失败，避免把确定性错误拖成长时间等待。
        """
        max_retries = max(0, int(self._s.fish_worker_embed_max_retries))
        attempt = 0

        while True:
            try:
                response = client.post(url, headers=headers, json=json)
                if response.status_code in _RETRYABLE_STATUS:
                    raise _RetryableHttpStatus(response)
                response.raise_for_status()
                return response

            except _RetryableHttpStatus as e:
                attempt += 1
                if attempt > max_retries:
                    e.response.raise_for_status()
                    raise
                delay = self._retry_delay(e.response.headers.get("Retry-After"), attempt)
                log.warning(
                    "embedding HTTP status=%s retry=%s/%s delay=%.2fs",
                    e.response.status_code,
                    attempt,
                    max_retries,
                    delay,
                )
                time.sleep(delay)

            except httpx.RequestError as e:
                attempt += 1
                if attempt > max_retries:
                    raise
                delay = self._retry_delay(None, attempt)
                log.warning(
                    "embedding request error=%s retry=%s/%s delay=%.2fs",
                    type(e).__name__,
                    attempt,
                    max_retries,
                    delay,
                )
                time.sleep(delay)

    def _retry_delay(self, retry_after: str | None, attempt: int) -> float:
        """计算指数退避时间，优先尊重 Retry-After，最多不超过配置上限。"""
        cap = max(0.0, float(self._s.fish_worker_embed_backoff_max))
        if cap <= 0:
            return 0.0
        if retry_after:
            try:
                return min(cap, max(0.0, float(retry_after)))
            except ValueError:
                pass

        base = max(0.0, float(self._s.fish_worker_embed_backoff_base))
        delay = min(cap, base * (2 ** max(0, attempt - 1)))
        jitter = random.uniform(0.0, min(0.5, cap))
        return min(cap, delay + jitter)
