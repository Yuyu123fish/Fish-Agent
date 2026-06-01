from __future__ import annotations

import unittest
from types import SimpleNamespace
from unittest.mock import patch

import httpx

from fish_worker.chunker.embedder import Embedder


class FakeResponse:
    def __init__(self, status_code: int, *, headers: dict[str, str] | None = None) -> None:
        self.status_code = status_code
        self.headers = headers or {}
        self.request = httpx.Request("POST", "https://example.test/embedding")

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            response = httpx.Response(self.status_code, request=self.request)
            raise httpx.HTTPStatusError("HTTP error", request=self.request, response=response)


class FakeClient:
    def __init__(self, outcomes: list[FakeResponse | Exception]) -> None:
        self._outcomes = outcomes
        self.calls = 0

    def post(self, *args, **kwargs) -> FakeResponse:
        self.calls += 1
        outcome = self._outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


def retry_settings(**overrides):
    defaults = {
        "fish_worker_embed_max_retries": 3,
        "fish_worker_embed_backoff_base": 0.0,
        "fish_worker_embed_backoff_max": 0.0,
    }
    defaults.update(overrides)
    return SimpleNamespace(**defaults)


class EmbedderRetryTest(unittest.TestCase):
    @patch("fish_worker.chunker.embedder.time.sleep")
    @patch("fish_worker.chunker.embedder.random.uniform", return_value=0.0)
    def test_retries_429_then_returns_success(self, _jitter, sleep) -> None:
        client = FakeClient([
            FakeResponse(429, headers={"Retry-After": "0"}),
            FakeResponse(200),
        ])

        response = Embedder(retry_settings())._post_with_retry(client, "https://example.test")

        self.assertEqual(200, response.status_code)
        self.assertEqual(2, client.calls)
        sleep.assert_called_once_with(0.0)

    @patch("fish_worker.chunker.embedder.time.sleep")
    def test_does_not_retry_400(self, sleep) -> None:
        client = FakeClient([FakeResponse(400)])

        with self.assertRaises(httpx.HTTPStatusError):
            Embedder(retry_settings())._post_with_retry(client, "https://example.test")

        self.assertEqual(1, client.calls)
        sleep.assert_not_called()

    @patch("fish_worker.chunker.embedder.time.sleep")
    @patch("fish_worker.chunker.embedder.random.uniform", return_value=0.0)
    def test_retries_network_errors(self, _jitter, sleep) -> None:
        request = httpx.Request("POST", "https://example.test/embedding")
        client = FakeClient([
            httpx.ConnectError("temporary network error", request=request),
            FakeResponse(200),
        ])

        response = Embedder(retry_settings())._post_with_retry(client, "https://example.test")

        self.assertEqual(200, response.status_code)
        self.assertEqual(2, client.calls)
        sleep.assert_called_once_with(0.0)


if __name__ == "__main__":
    unittest.main()
