# 环境变量配置 — 与 Java 侧 application.yml 命名完全对齐
#
# 核心库：pydantic-settings ≈ Spring Boot @ConfigurationProperties
#   - Field(validation_alias="REDIS_HOST")  → 从环境变量 REDIS_HOST 读取
#   - Settings() 构造时自动读取 .env 文件 + os.environ
#   - 类型声明 int / str 自带校验，类型不对直接抛 ValidationError
#
# JDBC URL 兼容：
#   Java 侧用 spring.data.redis 等独立 key，MySQL 却是 jdbc:mysql://... 单行
#   这里用正则解析 DB_URL（如果提供了），否则用 DB_HOST/DB_PORT/DB_NAME 组装
"""Environment configuration aligned with Fish-Agent Java application.yml."""

from __future__ import annotations

import re
from functools import cached_property
from pathlib import Path
from typing import Any

import pymysql.cursors
from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# .env 文件固定放在 python/ 目录下，用 __file__ 算出绝对路径
# 这样无论从哪个 CWD 启动都能找到（解决 pydantic-settings 默认相对 CWD 的问题）
_ENV_FILE = str(Path(__file__).resolve().parent.parent / ".env")

# 解析 jdbc:mysql://host:port/db?params 格式的 JDBC URL
# (?P<name>) 是 Python 正则的命名捕获组，等价于 Java 的 Matcher.group("name")
_JDBC_MYSQL = re.compile(
    r"^jdbc:mysql://(?P<host>[^:/]+)(?::(?P<port>\d+))?/(?P<db>[^?]+)",
    re.IGNORECASE,
)


class Settings(BaseSettings):
    # env_file 指向 .env 文件位置，env_file_encoding 指定编码
    # extra="ignore" 忽略未定义的额外环境变量（不会报错）
    model_config = SettingsConfigDict(
        env_file=_ENV_FILE,
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # ---- Redis（与 Spring Boot spring.data.redis 对齐）----
    redis_host: str = Field(default="localhost", validation_alias="REDIS_HOST")
    redis_port: int = Field(default=6379, validation_alias="REDIS_PORT")
    redis_password: str = Field(default="", validation_alias="REDIS_PASSWORD")
    redis_database: int = Field(default=2, validation_alias="REDIS_DATABASE")

    # ---- RustFS / MinIO（与 fish.rustfs 对齐）----
    rustfs_endpoint: str = Field(default="http://localhost:9000", validation_alias="RUSTFS_ENDPOINT")
    rustfs_access_key: str = Field(default="", validation_alias="RUSTFS_ACCESS_KEY")
    rustfs_secret_key: str = Field(default="", validation_alias="RUSTFS_SECRET_KEY")
    rustfs_bucket_docs: str = Field(default="fish-docs", validation_alias="RUSTFS_BUCKET_DOCS")

    # ---- Elasticsearch ----
    elasticsearch_uris: str = Field(
        default="http://localhost:9200", validation_alias="ELASTICSEARCH_URIS"
    )
    elasticsearch_username: str = Field(default="", validation_alias="ELASTICSEARCH_USERNAME")
    elasticsearch_password: str = Field(default="", validation_alias="ELASTICSEARCH_PASSWORD")

    # ---- MySQL（优先解析 DB_URL，否则用 DB_HOST/PORT/NAME 组装）----
    db_url: str | None = Field(default=None, validation_alias="DB_URL")
    db_host: str = Field(default="localhost", validation_alias="DB_HOST")
    db_port: int = Field(default=3306, validation_alias="DB_PORT")
    db_name: str = Field(default="fish_agent", validation_alias="DB_NAME")
    db_username: str = Field(default="root", validation_alias="DB_USERNAME")
    db_password: str = Field(default="", validation_alias="DB_PASSWORD")

    # ---- Redis Stream key / ES 索引名 ----
    fish_doc_ingest_stream: str = Field(default="fish:doc:ingest", validation_alias="FISH_DOC_INGEST_STREAM")
    # fish-user-memory：仅 Java 写入对话事实（source_type=chat）；Worker 不再写入此索引
    memory_user_index: str = Field(default="fish-user-memory", validation_alias="MEMORY_USER_INDEX")
    # fish-user-knowledge：用户上传文档切片（PRIVATE）；与 fish.knowledge.user-knowledge-index-name 对齐
    knowledge_user_index: str = Field(
        default="fish-user-knowledge", validation_alias="KNOWLEDGE_USER_INDEX"
    )
    knowledge_public_index: str = Field(
        default="fish-public-knowledge", validation_alias="KNOWLEDGE_PUBLIC_INDEX"
    )

    # ---- LLM Embedding（与 fish.llm.embedding 对齐）----
    fish_llm_embedding_provider: str = Field(
        default="DASHSCOPE", validation_alias="FISH_LLM_EMBEDDING_PROVIDER"
    )
    dashscope_api_key: str = Field(default="", validation_alias="DASHSCOPE_API_KEY")
    dashscope_embedding_model: str = Field(
        default="text-embedding-v2", validation_alias="DASHSCOPE_EMBEDDING_MODEL"
    )
    dashscope_embedding_dimensions: int = Field(
        default=1536, validation_alias="DASHSCOPE_EMBEDDING_DIMENSIONS"
    )
    ollama_base_url: str = Field(default="http://localhost:11434", validation_alias="OLLAMA_BASE_URL")
    ollama_embedding_model: str = Field(
        default="nomic-embed-text", validation_alias="OLLAMA_EMBEDDING_MODEL"
    )

    # ---- Worker 专属调优参数（Java 侧无对应项）----
    fish_worker_consumer_group: str = Field(
        default="fish-doc-worker-group", validation_alias="FISH_WORKER_CONSUMER_GROUP"
    )
    fish_worker_concurrency: int = Field(default=2, validation_alias="FISH_WORKER_CONCURRENCY")
    fish_worker_chunk_size: int = Field(default=512, validation_alias="FISH_WORKER_CHUNK_SIZE")
    fish_worker_chunk_overlap: int = Field(default=50, validation_alias="FISH_WORKER_CHUNK_OVERLAP")
    fish_worker_es_batch_size: int = Field(default=20, validation_alias="FISH_WORKER_ES_BATCH_SIZE")
    fish_worker_dashscope_embed_batch: int = Field(
        default=25, validation_alias="FISH_WORKER_DASHSCOPE_EMBED_BATCH"
    )
    fish_worker_block_ms: int = Field(default=5000, validation_alias="FISH_WORKER_BLOCK_MS")
    fish_worker_health_port: int = Field(default=8091, validation_alias="FISH_WORKER_HEALTH_PORT")

    # ---- 校验器 & 派生属性 ----

    # @field_validator 在字段赋值后执行，类似 Spring 的 @PostConstruct 或自定义 setter 校验
    # mode="before" 表示在类型转换之前执行
    @field_validator("fish_llm_embedding_provider", mode="before")
    @classmethod
    def _upper_provider(cls, v: Any) -> str:
        return str(v).strip().upper() if v is not None else "DASHSCOPE"

    # @cached_property 类似 Java 的懒加载单例：首次访问时计算，之后缓存，不会重复计算
    # 等价于 Guava 的 Suppliers.memoize() 或 Spring @Cacheable
    @cached_property
    def mysql_connect_kwargs(self) -> dict[str, Any]:
        """组装 PyMySQL 的连接参数。优先解析 DB_URL（JDBC 格式），否则用独立字段。"""
        host, port, database = self.db_host, self.db_port, self.db_name
        if self.db_url:
            m = _JDBC_MYSQL.match(self.db_url.strip())
            if m:
                host = m.group("host")
                database = m.group("db")
                port_s = m.group("port")
                if port_s:
                    port = int(port_s)
        return {
            "host": host,
            "port": port,
            "user": self.db_username,
            "password": self.db_password,
            "database": database,
            "charset": "utf8mb4",
            "cursorclass": pymysql.cursors.DictCursor,  # 查询结果返回 dict（类似 MyBatis 的 Map 结果集）
        }

    @cached_property
    def es_hosts(self) -> list[str]:
        """ELASTICSEARCH_URIS 支持逗号分隔多个节点，如 'http://es1:9200,http://es2:9200'."""
        parts = [p.strip() for p in self.elasticsearch_uris.split(",") if p.strip()]
        return parts or ["http://localhost:9200"]

    @cached_property
    def minio_endpoint_secure(self) -> tuple[str, bool]:
        """从 RUSTFS_ENDPOINT 拆分出 host:port 与是否 TLS。MinIO SDK 要求单独传 secure 参数。"""
        ep = self.rustfs_endpoint.strip()
        if ep.startswith("https://"):
            return ep.removeprefix("https://").rstrip("/"), True
        if ep.startswith("http://"):
            return ep.removeprefix("http://").rstrip("/"), False
        return ep.rstrip("/"), False


def load_settings() -> Settings:
    """实例化配置 —— 自动读取 .env + 环境变量并校验。"""
    return Settings()
