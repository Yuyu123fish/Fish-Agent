# 自定义异常层次 — 便于上层统一 catch 并区分错误类型
# 类比 Java：extends RuntimeException，不需要显式 throws 声明
"""Custom exception hierarchy for the ingest pipeline."""


class WorkerError(Exception):
    """Base worker error — all pipeline exceptions inherit from this."""


class UnsupportedFileTypeError(WorkerError):
    """No parser registered for the given MIME type (e.g. application/msword)."""
