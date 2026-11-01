from __future__ import annotations

from pathlib import Path

import psycopg

from .config import get_settings


def schema_sql(schema: str) -> str:
    if not schema or not all(c.isalnum() or c == "_" for c in schema):
        raise ValueError("schema 只能包含字母、数字和下划线")
    source = Path(__file__).resolve().parents[1] / "schema.sql"
    return source.read_text(encoding="utf-8").replace("mymoney_home", schema)


def apply_schema(conn: psycopg.Connection, schema: str) -> None:
    conn.execute(schema_sql(schema))
    conn.commit()


def main() -> None:
    settings = get_settings()
    with psycopg.connect(settings.database_url) as conn:
        apply_schema(conn, settings.schema)
    print(f"applied schema {settings.schema}")


if __name__ == "__main__":
    main()
