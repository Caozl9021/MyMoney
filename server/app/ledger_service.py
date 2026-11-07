from __future__ import annotations

import hashlib
import json
import uuid
from datetime import datetime, timezone
from typing import Any

from fastapi import HTTPException
from psycopg import Connection
from psycopg.types.json import Jsonb

from .config import get_settings
from .models import LedgerPushItem


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _hash(value: Any) -> str:
    return hashlib.sha256(_json(value).encode("utf-8")).hexdigest()


def _validate_payload(item: LedgerPushItem) -> None:
    if item.schemaVersion < 1:
        raise HTTPException(status_code=422, detail="不支持的账本格式版本")
    if item.operation == "delete":
        return
    if not isinstance(item.payload, dict):
        raise HTTPException(status_code=422, detail="账本条目内容为空")
    # 轻量校验：账单/账户/流水都必须带 syncId 与稳定 id，防止客户端误传本机自增 id
    if item.itemKind in ("bill", "account", "entry"):
        if not isinstance(item.payload.get("syncId"), str) or not item.payload.get("syncId"):
            raise HTTPException(status_code=422, detail="账本条目缺少 syncId")


def _ledger_revision(conn: Connection, space_id: str, ledger_id: str) -> int:
    """当前账本最大修订号（仅取 ledger_items 现存最大 revision，删除也保留行）。"""
    row = conn.execute(
        "SELECT COALESCE(MAX(revision), 0) AS rev FROM ledger_items WHERE space_id=%s AND ledger_id=%s",
        (space_id, ledger_id),
    ).fetchone()
    return int(row["rev"]) if row else 0


def _snapshot_row(row: dict) -> dict:
    return {
        "itemId": row["id"],
        "itemKind": row["item_kind"],
        "schemaVersion": row["schema_version"],
        "payload": row["payload"],
        "revision": row["revision"],
        "contentUpdatedAt": row["content_updated_at"],
        "deletedAt": row["deleted_at"].isoformat() if row.get("deleted_at") else None,
    }


def _record_result(conn: Connection, space_id: str, device_id: str, ledger_id: str,
                   operation_id: str, request_hash: str, item: LedgerPushItem, result: dict) -> None:
    conn.execute(
        """INSERT INTO ledger_operations(space_id, device_id, ledger_id, operation_id, request_hash, item_id, base_revision, result)
           VALUES (%s,%s,%s,%s,%s,%s,%s,%s)""",
        (space_id, device_id, ledger_id, operation_id, request_hash, item.itemId, item.baseRevision, Jsonb(result)),
    )


def push_item(conn: Connection, device: dict, space: dict, ledger_id: str, item: LedgerPushItem) -> dict:
    if item.epoch != str(space["epoch"]):
        raise HTTPException(status_code=409, detail="同步空间已重置，请重新对账")
    if item.ledgerId != ledger_id:
        raise HTTPException(status_code=422, detail="账本标识不一致")
    _validate_payload(item)
    request_hash = _hash(item.model_dump(mode="json"))
    previous = conn.execute(
        """SELECT request_hash, result FROM ledger_operations
           WHERE space_id=%s AND device_id=%s AND ledger_id=%s AND operation_id=%s""",
        (space["id"], device["device_id"], ledger_id, item.operationId),
    ).fetchone()
    if previous:
        if previous["request_hash"] != request_hash:
            raise HTTPException(status_code=409, detail="操作编号已用于不同内容")
        return previous["result"]

    current = conn.execute(
        "SELECT * FROM ledger_items WHERE space_id=%s AND ledger_id=%s AND id=%s FOR UPDATE",
        (space["id"], ledger_id, item.itemId),
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
        _record_result(conn, space["id"], device["device_id"], ledger_id, item.operationId, request_hash, item, result)
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
            _record_result(conn, space["id"], device["device_id"], ledger_id, item.operationId, request_hash, item, result)
            conn.commit()
            return result
        payload = current["payload"]
        item_kind = current["item_kind"]
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
            _record_result(conn, space["id"], device["device_id"], ledger_id, item.operationId, request_hash, item, result)
            conn.commit()
            return result
        payload = item.payload
        item_kind = item.itemKind
        deleted_at = None

    revision = _ledger_revision(conn, space["id"], ledger_id) + 1
    if current is None:
        row = conn.execute(
            """INSERT INTO ledger_items(space_id, ledger_id, id, item_kind, schema_version, payload,
                   revision, content_updated_at, updated_by, deleted_at)
               VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING *""",
            (space["id"], ledger_id, item.itemId, item_kind, item.schemaVersion, Jsonb(payload),
             revision, item.contentUpdatedAt, device["device_id"], deleted_at),
        ).fetchone()
    else:
        row = conn.execute(
            """UPDATE ledger_items SET item_kind=%s, schema_version=%s, payload=%s,
                   revision=%s, content_updated_at=%s, updated_by=%s, deleted_at=%s
               WHERE space_id=%s AND ledger_id=%s AND id=%s RETURNING *""",
            (item_kind, item.schemaVersion, Jsonb(payload), revision, item.contentUpdatedAt,
             device["device_id"], deleted_at, space["id"], ledger_id, item.itemId),
        ).fetchone()
    operation = "delete" if item.operation == "delete" else ("restore" if item.operation == "restore" else "put")
    snapshot = _snapshot_row(row)
    conn.execute(
        """INSERT INTO ledger_changes(space_id, ledger_id, revision, item_id, operation, snapshot, device_id)
           VALUES (%s,%s,%s,%s,%s,%s,%s)""",
        (space["id"], ledger_id, revision, item.itemId, operation, Jsonb(snapshot), device["device_id"]),
    )
    result = {"status": "ok", "operationId": item.operationId, "itemId": item.itemId,
              "itemKind": item_kind, "revision": revision, "snapshot": snapshot}
    _record_result(conn, space["id"], device["device_id"], ledger_id, item.operationId, request_hash, item, result)
    conn.commit()
    return result


def snapshot(conn: Connection, space: dict, ledger_id: str) -> dict:
    limit = get_settings().max_snapshot_items
    rows = conn.execute(
        "SELECT * FROM ledger_items WHERE space_id=%s AND ledger_id=%s ORDER BY item_kind, id LIMIT %s",
        (space["id"], ledger_id, limit + 1),
    ).fetchall()
    if len(rows) > limit:
        raise HTTPException(status_code=413, detail="账本快照超过上限，请先在 App 内整理")
    return {
        "protocolVersion": get_settings().protocol_version,
        "spaceId": str(space["id"]),
        "ledgerId": ledger_id,
        "epoch": str(space["epoch"]),
        "serverRevision": _ledger_revision(conn, space["id"], ledger_id),
        "items": [_snapshot_row(r) for r in rows],
    }


def pull(conn: Connection, space: dict, ledger_id: str, after: int, limit: int) -> dict:
    limit = max(1, min(limit, get_settings().max_pull_items))
    rows = conn.execute(
        """SELECT revision, item_id, operation, snapshot, device_id, recorded_at
           FROM ledger_changes WHERE space_id=%s AND ledger_id=%s AND revision > %s
           ORDER BY revision LIMIT %s""",
        (space["id"], ledger_id, after, limit + 1),
    ).fetchall()
    has_more = len(rows) > limit
    rows = rows[:limit]
    next_cursor = rows[-1]["revision"] if rows else after
    return {
        "protocolVersion": get_settings().protocol_version,
        "spaceId": str(space["id"]),
        "ledgerId": ledger_id,
        "epoch": str(space["epoch"]),
        "changes": [
            {
                "revision": r["revision"],
                "itemId": r["item_id"],
                "operation": r["operation"],
                "snapshot": r["snapshot"],
                "deviceId": str(r["device_id"]),
                "recordedAt": r["recorded_at"].isoformat(),
            }
            for r in rows
        ],
        "nextCursor": next_cursor,
        "hasMore": has_more,
        "serverRevision": _ledger_revision(conn, space["id"], ledger_id),
    }


def ack(conn: Connection, device: dict, space: dict, ledger_id: str, through_revision: int, epoch: str) -> None:
    if epoch != str(space["epoch"]):
        raise HTTPException(status_code=409, detail="同步空间已重置，请重新对账")
    target = max(0, min(through_revision, _ledger_revision(conn, space["id"], ledger_id)))
    conn.execute(
        """INSERT INTO ledger_device_cursors(space_id, device_id, ledger_id, last_pulled_revision, updated_at)
           VALUES (%s,%s,%s,%s,now())
           ON CONFLICT (space_id, device_id, ledger_id)
           DO UPDATE SET last_pulled_revision=GREATEST(ledger_device_cursors.last_pulled_revision, EXCLUDED.last_pulled_revision), updated_at=now()""",
        (space["id"], device["device_id"], ledger_id, target),
    )
    conn.commit()


# ── W3: 跨设备按需导入（冻结来源快照）─────────────────────────────
def _device_in_space(conn: Connection, space_id: str, device_id: str) -> dict:
    row = conn.execute(
        "SELECT id, name, revoked_at FROM devices WHERE space_id=%s AND id=%s",
        (space_id, device_id),
    ).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="来源设备不存在")
    if row["revoked_at"] is not None:
        raise HTTPException(status_code=403, detail="来源设备已撤销")
    return row


def import_source_snapshot(conn: Connection, device: dict, space: dict,
                           source_device_id: str, source_ledger_id: str) -> dict:
    """B 读取同空间另一设备 A 的账本冻结快照（只读，含墓碑），并登记导入快照元数据。

    权限：来源设备必须属于当前 space 且未撤销；不能跨 space 读取。
    冻结：以本次读取到的 serverRevision 为冻结点，返回该修订下全部条目（含墓碑），
    后续 B 只把这份快照按用户选择导入，不建立订阅。
    """
    if source_device_id == str(device["device_id"]):
        raise HTTPException(status_code=422, detail="不能从本设备导入")
    _device_in_space(conn, space["id"], source_device_id)

    # 一致性读：REPEATABLE READ 由调用方连接隔离级别保证；此处单事务内读全部条目 + 修订号。
    revision = _ledger_revision(conn, space["id"], source_ledger_id)
    limit = get_settings().max_snapshot_items
    rows = conn.execute(
        "SELECT * FROM ledger_items WHERE space_id=%s AND ledger_id=%s ORDER BY item_kind, id LIMIT %s",
        (space["id"], source_ledger_id, limit + 1),
    ).fetchall()
    if len(rows) > limit:
        raise HTTPException(status_code=413, detail="来源账本超过上限，无法整体导入")

    snapshot_id = str(uuid.uuid4())
    conn.execute(
        """INSERT INTO ledger_import_snapshots
           (id, space_id, source_device_id, source_ledger_id, target_device_id, source_revision, item_count)
           VALUES (%s,%s,%s,%s,%s,%s,%s)""",
        (snapshot_id, space["id"], source_device_id, source_ledger_id,
         device["device_id"], revision, len(rows)),
    )
    conn.commit()
    return {
        "protocolVersion": get_settings().protocol_version,
        "spaceId": str(space["id"]),
        "importSnapshotId": snapshot_id,
        "sourceLedgerId": source_ledger_id,
        "sourceRevision": revision,
        "items": [_snapshot_row(r) for r in rows],
    }


def list_import_sources(conn: Connection, device: dict, space: dict) -> dict:
    """列出同空间可导入的来源设备（排除自己、已撤销）。每个来源设备给出其最近有数据的 ledger_id 与条目计数。"""
    rows = conn.execute(
        "SELECT id, name FROM devices WHERE space_id=%s AND id<>%s AND revoked_at IS NULL ORDER BY created_at",
        (space["id"], device["device_id"]),
    ).fetchall()
    sources = []
    for r in rows:
        latest = conn.execute(
            """SELECT ledger_id, COUNT(*) AS cnt, MAX(revision) AS rev
               FROM ledger_items WHERE space_id=%s AND updated_by=%s AND deleted_at IS NULL
               GROUP BY ledger_id ORDER BY rev DESC LIMIT 1""",
            (space["id"], r["id"]),
        ).fetchone()
        if latest and latest["cnt"] > 0:
            sources.append({
                "deviceId": str(r["id"]),
                "deviceName": r["name"],
                "ledgerId": str(latest["ledger_id"]),
                "serverRevision": int(latest["rev"] or 0),
                "itemCount": int(latest["cnt"]),
            })
    return {
        "protocolVersion": get_settings().protocol_version,
        "spaceId": str(space["id"]),
        "sources": sources,
    }
