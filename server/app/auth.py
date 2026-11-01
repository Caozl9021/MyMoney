from __future__ import annotations

import hashlib
import secrets

from fastapi import Depends, Header, HTTPException
from psycopg import Connection

from .db import connection


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def new_token() -> str:
    return secrets.token_urlsafe(32)


def current_device(
    authorization: str = Header(default=""),
) -> dict:
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="未登录")
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(status_code=401, detail="未登录")
    with connection() as conn:
        row = conn.execute(
            """SELECT d.space_id, d.id AS device_id, d.name AS device_name,
                      d.last_pulled_revision, s.epoch, s.current_revision
               FROM devices d JOIN sync_spaces s ON s.id = d.space_id
               WHERE d.token_hash = %s AND d.revoked_at IS NULL""",
            (hash_token(token),),
        ).fetchone()
    if not row:
        raise HTTPException(status_code=401, detail="设备令牌无效或已撤销")
    row = dict(row)
    row["space_id"] = str(row["space_id"])
    row["device_id"] = str(row["device_id"])
    row["epoch"] = str(row["epoch"])
    return row
