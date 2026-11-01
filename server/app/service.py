from __future__ import annotations

import hashlib
import json
import re
import uuid
from datetime import datetime, timezone
from typing import Any

from fastapi import HTTPException
from psycopg import Connection
from psycopg.types.json import Jsonb

from .auth import hash_token, new_token
from .config import get_settings
from .models import PushItem


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _hash(value: Any) -> str:
    return hashlib.sha256(_json(value).encode("utf-8")).hexdigest()


def _normalize_merchant(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip()).lower()


def _natural_key(item: PushItem) -> str:
    payload = item.payload or {}
    if item.kind == "recognition_rule":
        return item.itemId
    if item.kind == "merchant_note_rule":
        app = payload.get("app")
        kind = payload.get("type")
        merchant = payload.get("merchant")
        if not all(isinstance(v, str) and v for v in (app, kind, merchant)):
            raise HTTPException(status_code=422, detail="商家备注规则缺少 app/type/merchant")
        return f"{app}|{kind}|{_normalize_merchant(merchant)}"
    if item.kind == "official_rule_switch":
        if not item.naturalKey or len(item.naturalKey) > 128:
            raise HTTPException(status_code=422, detail="官方规则开关缺少规则标识")
        return item.naturalKey
    if item.kind == "note_defaults":
        return "defaults"
    raise HTTPException(status_code=422, detail="未知规则类型")


def _validate_payload(item: PushItem) -> None:
    if item.schemaVersion != 1:
        raise HTTPException(status_code=422, detail="不支持的规则格式版本")
    if item.operation == "delete":
        return
    if not isinstance(item.payload, dict):
        raise HTTPException(status_code=422, detail="规则内容为空")
    if item.kind == "recognition_rule":
        if not isinstance(item.payload, dict) or item.payload.get("id") != item.itemId:
            raise HTTPException(status_code=422, detail="个人规则标识不匹配")
    elif item.kind == "merchant_note_rule":
        if not isinstance(item.payload, dict):
            raise HTTPException(status_code=422, detail="商家备注内容为空")
        mode = item.payload.get("mode")
        if mode not in ("PRODUCT", "FIXED", "NONE"):
            raise HTTPException(status_code=422, detail="商家备注模式无效")
    elif item.kind == "official_rule_switch":
        if not isinstance(item.payload, dict) or not isinstance(item.payload.get("enabled"), bool):
            raise HTTPException(status_code=422, detail="官方规则开关无效")
    elif item.kind == "note_defaults":
        if not isinstance(item.payload, dict) or not isinstance(item.payload.get("productFirst"), bool):
            raise HTTPException(status_code=422, detail="备注默认设置无效")
        template = item.payload.get("noteTemplate")
        if not isinstance(template, str) or len(template) > 200:
            raise HTTPException(status_code=422, detail="备注模板无效")


def ensure_space(conn: Connection, name: str = "家庭账本") -> dict:
    row = conn.execute("SELECT * FROM sync_spaces ORDER BY created_at, id LIMIT 1").fetchone()
    if row:
        return row
    space_id = str(uuid.uuid4())
    epoch = str(uuid.uuid4())
    row = conn.execute(
        """INSERT INTO sync_spaces(id, epoch, name, current_revision)
           VALUES (%s, %s, %s, 0) RETURNING *""",
        (space_id, epoch, name[:120]),
    ).fetchone()
    return row


def pair_device(conn: Connection, code: str, device_name: str) -> dict:
    from .pairing import PAIRING

    if not PAIRING.verify(code):
        raise HTTPException(status_code=401, detail="配对码无效或已过期")
    space = ensure_space(conn)
    device_id = str(uuid.uuid4())
    token = new_token()
    conn.execute(
        """INSERT INTO devices(space_id, id, name, token_hash)
           VALUES (%s, %s, %s, %s)""",
        (space["space_id"] if "space_id" in space else space["id"], device_id, device_name[:80], hash_token(token)),
    )
    conn.commit()
    return {
        "protocolVersion": get_settings().protocol_version,
        "spaceId": str(space.get("id")),
        "epoch": str(space["epoch"]),
        "deviceId": device_id,
        "token": token,
    }


def _snapshot_row(row: dict) -> dict:
    return {
        "itemId": row["id"],
        "kind": row["kind"],
        "schemaVersion": row["schema_version"],
        "payload": row["payload"],
        "naturalKey": row["natural_key"],
        "sortKey": row["sort_key"],
        "revision": row["revision"],
        "contentUpdatedAt": row["content_updated_at"],
        "deletedAt": row["deleted_at"].isoformat() if row.get("deleted_at") else None,
    }


def _record_result(conn: Connection, space_id: str, device_id: str, operation_id: str, request_hash: str, item: PushItem, result: dict) -> None:
    conn.execute(
        """INSERT INTO sync_operations(space_id, device_id, operation_id, request_hash, item_id, base_revision, result)
           VALUES (%s, %s, %s, %s, %s, %s, %s)""",
        (space_id, device_id, operation_id, request_hash, item.itemId, item.baseRevision, Jsonb(result)),
    )


def push_item(conn: Connection, device: dict, space: dict, item: PushItem) -> dict:
    if item.epoch != str(space["epoch"]):
        raise HTTPException(status_code=409, detail="同步空间已重置，请重新对账")
    _validate_payload(item)
    natural_key = _natural_key(item) if item.operation != "delete" else None
    request_hash = _hash(item.model_dump(mode="json"))
    previous = conn.execute(
        """SELECT request_hash, result FROM sync_operations
           WHERE space_id=%s AND device_id=%s AND operation_id=%s""",
        (space["id"], device["device_id"], item.operationId),
    ).fetchone()
    if previous:
        if previous["request_hash"] != request_hash:
            raise HTTPException(status_code=409, detail="操作编号已用于不同内容")
        return previous["result"]

    current = conn.execute(
        "SELECT * FROM rule_items WHERE space_id=%s AND id=%s FOR UPDATE",
        (space["id"], item.itemId),
    ).fetchone()
    current_revision = current["revision"] if current else 0
    if item.baseRevision != current_revision:
        result = {
            "status": "conflict",
            "operationId": item.operationId,
            "itemId": item.itemId,
            "baseRevision": item.baseRevision,
            "remoteRevision": current_revision,
            "remote": _snapshot_row(current) if current else None,
        }
        _record_result(conn, space["id"], device["device_id"], item.operationId, request_hash, item, result)
        conn.commit()
        return result

    if item.operation == "delete":
        if current is None or current["deleted_at"] is not None:
            result = {
                "status": "conflict",
                "operationId": item.operationId,
                "itemId": item.itemId,
                "baseRevision": item.baseRevision,
                "remoteRevision": current_revision,
                "remote": _snapshot_row(current) if current else None,
            }
            _record_result(conn, space["id"], device["device_id"], item.operationId, request_hash, item, result)
            conn.commit()
            return result
        payload = current["payload"]
        natural_key = current["natural_key"]
        deleted_at = datetime.now(timezone.utc)
    else:
        if current is not None and current["deleted_at"] is not None and item.operation != "restore":
            result = {
                "status": "conflict",
                "operationId": item.operationId,
                "itemId": item.itemId,
                "baseRevision": item.baseRevision,
                "remoteRevision": current_revision,
                "remote": _snapshot_row(current),
                "error": "remote_deleted",
            }
            _record_result(conn, space["id"], device["device_id"], item.operationId, request_hash, item, result)
            conn.commit()
            return result
        payload = item.payload
        deleted_at = None

    revision = space["current_revision"] + 1
    if current is None:
        row = conn.execute(
            """INSERT INTO rule_items(space_id, id, kind, schema_version, payload, natural_key,
                   sort_key, revision, content_updated_at, updated_by, deleted_at)
               VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING *""",
            (space["id"], item.itemId, item.kind, item.schemaVersion, Jsonb(payload), natural_key,
             item.sortKey or "", revision, item.contentUpdatedAt, device["device_id"], deleted_at),
        ).fetchone()
    else:
        row = conn.execute(
            """UPDATE rule_items SET kind=%s, schema_version=%s, payload=%s, natural_key=%s,
                   sort_key=%s, revision=%s, content_updated_at=%s, updated_by=%s, deleted_at=%s
               WHERE space_id=%s AND id=%s RETURNING *""",
            (item.kind, item.schemaVersion, Jsonb(payload), natural_key, item.sortKey or "",
             revision, item.contentUpdatedAt, device["device_id"], deleted_at, space["id"], item.itemId),
        ).fetchone()
    conn.execute("UPDATE sync_spaces SET current_revision=%s WHERE id=%s", (revision, space["id"]))
    operation = "delete" if item.operation == "delete" else ("restore" if item.operation == "restore" else "put")
    snapshot = _snapshot_row(row)
    conn.execute(
        """INSERT INTO sync_changes(space_id, revision, item_id, operation, snapshot, device_id)
           VALUES (%s,%s,%s,%s,%s,%s)""",
        (space["id"], revision, item.itemId, operation, Jsonb(snapshot), device["device_id"]),
    )
    result = {"status": "ok", "operationId": item.operationId, "itemId": item.itemId, "revision": revision, "snapshot": snapshot}
    _record_result(conn, space["id"], device["device_id"], item.operationId, request_hash, item, result)
    conn.commit()
    # The caller keeps one space row for all items in a push batch; advance it so
    # subsequent items in the same transaction use the next revision.
    space["current_revision"] = revision
    return result


def snapshot(conn: Connection, space: dict) -> dict:
    limit = get_settings().max_snapshot_items
    rows = conn.execute(
        "SELECT * FROM rule_items WHERE space_id=%s ORDER BY kind, sort_key, id LIMIT %s",
        (space["id"], limit + 1),
    ).fetchall()
    if len(rows) > limit:
        raise HTTPException(status_code=413, detail="规则快照超过上限，请先在 App 内整理")
    return {
        "protocolVersion": get_settings().protocol_version,
        "spaceId": str(space["id"]),
        "epoch": str(space["epoch"]),
        "serverRevision": space["current_revision"],
        "items": [_snapshot_row(r) for r in rows],
    }


def pull(conn: Connection, space: dict, after: int, limit: int) -> dict:
    limit = max(1, min(limit, get_settings().max_pull_items))
    rows = conn.execute(
        """SELECT revision, item_id, operation, snapshot, device_id, recorded_at
           FROM sync_changes WHERE space_id=%s AND revision > %s
           ORDER BY revision LIMIT %s""",
        (space["id"], after, limit + 1),
    ).fetchall()
    has_more = len(rows) > limit
    rows = rows[:limit]
    next_cursor = rows[-1]["revision"] if rows else after
    return {
        "protocolVersion": get_settings().protocol_version,
        "spaceId": str(space["id"]),
        "epoch": str(space["epoch"]),
        "changes": [
            {
                "revision": r["revision"],
                "itemId": r["item_id"],
                "operation": r["operation"],
                "snapshot": r["snapshot"],
                "deviceId": r["device_id"],
                "recordedAt": r["recorded_at"].isoformat(),
            }
            for r in rows
        ],
        "nextCursor": next_cursor,
        "hasMore": has_more,
        "serverRevision": space["current_revision"],
    }


def ack(conn: Connection, device: dict, through_revision: int, epoch: str) -> None:
    if epoch != device["epoch"]:
        raise HTTPException(status_code=409, detail="同步空间已重置，请重新对账")
    target = max(0, min(through_revision, device["current_revision"]))
    conn.execute(
        """UPDATE devices SET last_pulled_revision=GREATEST(last_pulled_revision, %s), last_seen_at=now()
           WHERE space_id=%s AND id=%s""",
        (target, device["space_id"], device["device_id"]),
    )
    conn.commit()
