from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    database_url: str
    schema: str
    backup_dir: Path
    admin_token: str
    pairing_code: str | None
    pairing_ttl_seconds: int = 300
    protocol_version: int = 1
    max_push_items: int = 50
    max_push_bytes: int = 512 * 1024
    max_pull_items: int = 100
    max_snapshot_items: int = 500
    max_manifest_bytes: int = 64 * 1024
    max_backup_bytes: int = 200 * 1024 * 1024

    @classmethod
    def from_env(cls) -> "Settings":
        schema = os.getenv("MYMONEY_DB_SCHEMA", "mymoney_home").strip()
        if not schema or not all(c.isalnum() or c == "_" for c in schema):
            raise RuntimeError("MYMONEY_DB_SCHEMA 只能是字母、数字和下划线")
        try:
            pairing_ttl_seconds = int(os.getenv("MYMONEY_PAIRING_TTL_SECONDS", "300"))
        except ValueError as exc:
            raise RuntimeError("MYMONEY_PAIRING_TTL_SECONDS 必须是整数，0 表示永久") from exc
        if pairing_ttl_seconds < 0:
            raise RuntimeError("MYMONEY_PAIRING_TTL_SECONDS 不能为负数")
        database_url = os.getenv("MYMONEY_DATABASE_URL", "").strip()
        if not database_url:
            raise RuntimeError("请通过 MYMONEY_DATABASE_URL 配置数据库连接")
        return cls(
            database_url=database_url,
            schema=schema,
            backup_dir=Path(os.getenv("MYMONEY_BACKUP_DIR", "/srv/mymoney-home/backups")),
            admin_token=os.getenv("MYMONEY_ADMIN_TOKEN", ""),
            pairing_code=os.getenv("MYMONEY_PAIRING_CODE") or None,
            pairing_ttl_seconds=pairing_ttl_seconds,
        )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings.from_env()
