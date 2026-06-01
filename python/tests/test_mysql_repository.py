from __future__ import annotations

import unittest
from types import SimpleNamespace
from unittest.mock import patch

from fish_worker.db.mysql import DocumentMetadataRepository


class FakeCursor:
    def __init__(self, conn: "FakeConnection") -> None:
        self._conn = conn

    def __enter__(self) -> "FakeCursor":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        return None

    def execute(self, sql: str, params: list[object]) -> int:
        self._conn.executed.append((sql, tuple(params)))
        return self._conn.rowcount


class FakeConnection:
    def __init__(self, *, rowcount: int = 1) -> None:
        self.rowcount = rowcount
        self.executed: list[tuple[str, tuple[object, ...]]] = []
        self.commits = 0
        self.closed = False

    def __enter__(self) -> "FakeConnection":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        return None

    def cursor(self) -> FakeCursor:
        return FakeCursor(self)

    def commit(self) -> None:
        self.commits += 1

    def ping(self, reconnect: bool = True) -> None:
        return None

    def close(self) -> None:
        self.closed = True


class DocumentMetadataRepositoryTest(unittest.TestCase):
    def _repo_with_conn(self, conn: FakeConnection) -> DocumentMetadataRepository:
        settings = SimpleNamespace(mysql_connect_kwargs={})
        patcher = patch("fish_worker.db.mysql.pymysql.connect", return_value=conn)
        self.addCleanup(patcher.stop)
        patcher.start()
        return DocumentMetadataRepository(settings)

    def test_update_status_can_compare_expected_status(self) -> None:
        conn = FakeConnection(rowcount=0)
        repo = self._repo_with_conn(conn)

        affected = repo.update_status(
            "task-1",
            "SUCCESS",
            chunk_count=3,
            expected_status="PROCESSING",
        )

        self.assertEqual(0, affected)
        sql, params = conn.executed[0]
        self.assertIn("WHERE task_id=%s AND status=%s", sql)
        self.assertEqual(("SUCCESS", 3, "task-1", "PROCESSING"), params)
        self.assertEqual(1, conn.commits)

    def test_touch_updates_only_processing_rows(self) -> None:
        conn = FakeConnection(rowcount=1)
        repo = self._repo_with_conn(conn)

        affected = repo.touch("task-2")

        self.assertEqual(1, affected)
        sql, params = conn.executed[0]
        self.assertIn("updated_at=NOW()", sql)
        self.assertIn("status='PROCESSING'", sql)
        self.assertEqual(("task-2",), params)

    def test_close_current_thread_conn_closes_and_clears_cached_connection(self) -> None:
        conn = FakeConnection()
        repo = self._repo_with_conn(conn)

        repo.touch("task-3")
        repo.close_current_thread_conn()

        self.assertTrue(conn.closed)
        self.assertIsNone(getattr(repo._local, "conn", None))


if __name__ == "__main__":
    unittest.main()
