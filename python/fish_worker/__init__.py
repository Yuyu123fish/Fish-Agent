# Fish-Agent Python Worker (v2.2)
# 职责：消费 Redis Stream → 解析 PDF → 分块 → embedding → 写入 ES → 更新 MySQL
# 与 Java 上传链路（v2.1）通过 Redis Stream 解耦，双方互不感知
#
# Python 包结构说明（类比 Java package）：
#   fish_worker/__init__.py  ← 声明这是一个 Python package（相当于放一个 package-info.java）
#   __version__              ← 包级别常量，其他模块可以 import fish_worker; fish_worker.__version__
"""Fish-Agent document ingest worker: consumes Redis Stream, parses PDFs, indexes ES."""

__version__ = "0.1.0"
