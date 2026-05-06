# 手工 DI 容器 —— 避免循环导入的集中装配点
#
# 类比 Java：Spring 的 @Component 扫描 + @Autowired 注入
# 这里用最简单的 dataclass 做依赖聚合，由 main.py 在启动时手动组装
#
# @dataclass 相当于 Lombok 的 @Data / Java 17 的 record：
#   自动生成 __init__(self, settings, minio, db, es, embedder)
#   省去手写构造函数
"""Shared worker dependencies (avoid circular imports)."""

from __future__ import annotations

from dataclasses import dataclass

from fish_worker.chunker.embedder import Embedder
from fish_worker.config import Settings
from fish_worker.db.mysql import DocumentMetadataRepository
from fish_worker.storage.elasticsearch import ElasticsearchIndexer
from fish_worker.storage.minio import DocObjectStore


@dataclass
class WorkerContext:
    settings: Settings
    minio: DocObjectStore
    db: DocumentMetadataRepository
    es: ElasticsearchIndexer
    embedder: Embedder
