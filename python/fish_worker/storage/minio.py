# RustFS / MinIO 客户端 — 从 fish-docs 桶下载原始文件
#
# Java 侧 putDocObject 时已设置正确的 Content-Type
# 这里直接从 HTTP 响应头读取，不从文件名猜测
"""RustFS / MinIO client: download originals from fish-docs bucket."""

from __future__ import annotations

from minio import Minio

from fish_worker.config import Settings


class DocObjectStore:

    def __init__(self, settings: Settings) -> None:
        # MinIO SDK 要求分别传 endpoint 和 secure（是否 TLS）
        endpoint, secure = settings.minio_endpoint_secure
        self._client = Minio(
            endpoint,
            access_key=settings.rustfs_access_key,
            secret_key=settings.rustfs_secret_key,
            secure=secure,
        )
        self._bucket = settings.rustfs_bucket_docs

    def ping_bucket(self) -> bool:
        """检查桶是否存在（用于 health check）。"""
        return bool(self._client.bucket_exists(self._bucket))

    def get_object(self, object_key: str) -> tuple[bytes, str]:
        """下载对象并返回 (文件字节, MIME 类型)。

        Content-Type 来源于 Java 侧上传时设置的 HTTP 头，
        例如 application/pdf、application/octet-stream 等。
        """
        resp = self._client.get_object(self._bucket, object_key)
        try:
            data = resp.read()
            # minio-py 的 HTTP 响应头字段名可能大小写不一致，两种都查
            headers = getattr(resp, "headers", {}) or {}
            ct = (
                headers.get("Content-Type")
                or headers.get("content-type")
                or "application/octet-stream"
            )
            # 去掉 charset 等参数：'application/pdf; charset=utf-8' → 'application/pdf'
            return data, str(ct).split(";")[0].strip()
        finally:
            # 必须显式关闭，否则连接泄漏
            resp.close()
            resp.release_conn()
