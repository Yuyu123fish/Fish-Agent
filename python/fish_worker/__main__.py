# 让 python -m fish_worker 能够直接启动 Worker
# 类比：Java 的 Main-Class 清单入口，或 Spring Boot 的 @SpringBootApplication main 方法
#
# Python 的 -m 机制：
#   python -m fish_worker         → 执行 fish_worker/__main__.py
#   python -m fish_worker.main    → 执行 fish_worker/main.py 的 if __name__ == "__main__" 块
# 两种方式都能启动，Dockerfile 使用第一种（更简洁）
"""Allow ``python -m fish_worker``."""

from fish_worker.main import main

if __name__ == "__main__":
    raise SystemExit(main())
