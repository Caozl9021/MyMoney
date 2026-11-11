"""Immutable JSON8/9 backup files; only committed indexes are downloadable.

Deletion (Y) is a tombstone plus asynchronous file recycle: the transaction
only flips deleted_at and records the operation; files are removed after the
commit by the same request when possible, or by a startup recovery sweep.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from .auth import current_device
from .config import get_settings
from .db import connection

router = APIRouter()

MAX_DELETE_BATCH = 100


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _integer(value, *, positive=False):
    if type(value) is not int or not -9223372036854775808 <= value <= 9223372036854775807:
        raise ValueError()
    if positive and value <= 0:
        raise ValueError()
    return value


def validate_deletion(event: object) -> None:
    """Audit snapshots remain separate from live bills and must retain their original group."""
    if not isinstance(event, dict):
        raise ValueError()
    for key in ("eventId", "groupId", "origin", "snapshotJson"):
        if not isinstance(event.get(key), str) or not event[key].strip():
            raise ValueError()
    if str(UUID(event["eventId"])) != event["eventId"] or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", event["origin"]):
        raise ValueError()
    if len(event["snapshotJson"].encode("utf-8")) > 1024 * 1024:
        raise ValueError()
    _integer(event.get("deletedAt"), positive=True)
    _integer(event.get("principalId"), positive=True)
    if type(event.get("snapshotVersion")) is not int or event["snapshotVersion"] != 1:
        raise ValueError()
    if event.get("recognitionId") is not None and (not isinstance(event["recognitionId"], str) or not event["recognitionId"].strip()):
        raise ValueError()
    snapshot = json.loads(event["snapshotJson"], parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
    if not isinstance(snapshot, dict) or not isinstance(snapshot.get("bills"), list) or not 1 <= len(snapshot["bills"]) <= 100:
        raise ValueError()
    rows = snapshot["bills"]
    ids = set()
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError()
        identity = _integer(row.get("id"), positive=True)
        if identity in ids:
            raise ValueError()
        ids.add(identity)
        _integer(row.get("priceCents"), positive=True)
        _integer(row.get("timeMillis"))
        _integer(row.get("createdAt"))
        if row.get("type") not in ("EXPENSE", "INCOME", "TRANSFER", "REFUND"):
            raise ValueError()
        for key in ("sourcePkg", "strategy", "fingerprint", "assetKey"):
            if not isinstance(row.get(key), str):
                raise ValueError()
        for key in ("merchant", "method", "orderTail", "category", "subCategory", "account", "note", "transferAccount", "productName", "productSource", "noteOrigin", "noteTemplateSnapshot"):
            if row.get(key) is not None and not isinstance(row[key], str):
                raise ValueError()
        for key in ("recognitionId", "accountId", "transferAccountId", "feeParentKey"):
            if row.get(key) is not None and (not isinstance(row[key], str) or not row[key].strip()):
                raise ValueError()
        if type(row.get("assetLinked")) is not bool:
            raise ValueError()
        fee = _integer(row.get("transferFeeCents"))
        if fee < 0:
            raise ValueError()
    principal = rows[0]
    if principal["id"] != event["principalId"] or principal.get("feeParentKey") is not None:
        raise ValueError()
    if principal.get("recognitionId") != event.get("recognitionId"):
        raise ValueError()
    group = principal["assetKey"] if principal["assetKey"].strip() else f"bill:{principal['id']}"
    if event["groupId"] != group:
        raise ValueError()
    fees = rows[1:]
    if principal["type"] != "TRANSFER":
        if fees or principal["transferFeeCents"] != 0:
            raise ValueError()
    else:
        expected_fee = principal["transferFeeCents"]
        if len(fees) != (1 if expected_fee > 0 else 0):
            raise ValueError()
        if fees:
            fee = fees[0]
            if (not principal["assetKey"].strip() or fee["type"] != "EXPENSE" or
                    fee.get("feeParentKey") != principal["assetKey"] or
                    fee["priceCents"] != expected_fee or fee.get("accountId") != principal.get("accountId")):
                raise ValueError()


def validate_snapshot(path: Path) -> tuple[int, datetime, int]:
    """Validate the portable envelope and actual rows; Android validates all relations."""
    try:
        with path.open("r", encoding="utf-8") as stream:
            data = json.load(stream, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        if not isinstance(data, dict) or data.get("kind") != "MyMoneyBackup" or type(data.get("format")) is not int or data["format"] not in (8, 9, 10, 11, 12):
            raise ValueError()
        timestamp = data.get("exportedAt")
        if type(timestamp) is not int or timestamp <= 0:
            raise ValueError()
        snapshot_at = datetime.fromtimestamp(timestamp / 1000, timezone.utc)
        bills = data.get("bills")
        if not isinstance(bills, list):
            raise ValueError()
        for bill in bills:
            if not isinstance(bill, dict) or type(bill.get("p")) is not int or not 0 < bill["p"] <= 9223372036854775807:
                raise ValueError()
            if bill.get("ty") not in ("EXPENSE", "INCOME", "TRANSFER", "REFUND") or type(bill.get("t")) is not int:
                raise ValueError()
            for key in ("fp", "sp", "st"):
                if not isinstance(bill.get(key), str):
                    raise ValueError()
        if data["format"] >= 10:
            sync_ids = set()
            for bill in bills:
                sid = bill.get("sid", "")
                if not isinstance(sid, str) or len(sid) > 128 or (sid and sid in sync_ids):
                    raise ValueError()
                if sid:
                    sync_ids.add(sid)
        for key in ("history", "imports"):
            if data.get(key) is not None and (not isinstance(data[key], list) or any(not isinstance(row, dict) for row in data[key])):
                raise ValueError()
        for key in ("assets", "capture", "ledger", "settings"):
            if data.get(key) is not None and not isinstance(data[key], dict):
                raise ValueError()
        if "deletions" in data:
            if data["format"] < 9 or not isinstance(data["deletions"], list):
                raise ValueError()
            event_ids = set()
            for event in data["deletions"]:
                validate_deletion(event)
                if event["eventId"] in event_ids:
                    raise ValueError()
                event_ids.add(event["eventId"])
        return len(bills), snapshot_at, data["format"]
    except (ValueError, TypeError, UnicodeError, RecursionError, OverflowError, OSError) as exc:
        raise HTTPException(status_code=422, detail="备份不是有效的 JSON8/JSON9/JSON10 快照；请检查文件或升级家庭服务") from exc


def metadata(row: dict) -> dict:
    return {
        "id": str(row["id"]), "deviceId": str(row["device_id"]),
        "deviceName": row["device_name"], "appVersion": row["app_version"],
        "backupSchema": row["backup_schema"], "sha256": row["sha256"].strip(),
        "sizeBytes": row["size_bytes"], "billCount": row["bill_count"],
        "snapshotAt": row["snapshot_at"].isoformat(), "uploadedAt": row["uploaded_at"].isoformat(),
    }


SELECT = """SELECT b.*, d.name AS device_name FROM ledger_backups b
            JOIN devices d ON d.space_id=b.space_id AND d.id=b.device_id"""


def _backup_file(storage_key: str) -> Path:
    root = get_settings().backup_dir.resolve()
    path = (root / storage_key).resolve()
    if not path.is_relative_to(root):
        raise HTTPException(status_code=500, detail="备份索引路径无效")
    return path


def _sync_directory(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


@router.put("/v1/backups/{backup_id}")
async def upload_backup(
    backup_id: UUID, request: Request,
    x_content_sha256: str = Header(default=""), x_app_version: str = Header(default=""),
    x_space_epoch: str = Header(default=""), device: dict = Depends(current_device),
    authorization: str = Header(default=""),
) -> dict:
    settings = get_settings()
    if x_space_epoch != device["epoch"]:
        raise HTTPException(status_code=409, detail="家庭空间已重置，请重新对账")
    if not re.fullmatch(r"[0-9a-f]{64}", x_content_sha256) or not 1 <= len(x_app_version) <= 80:
        raise HTTPException(status_code=422, detail="备份摘要或应用版本无效")
    if request.headers.get("content-encoding", "identity").lower() != "identity":
        raise HTTPException(status_code=415, detail="仅接受未压缩 JSON8/JSON9/JSON10 快照")
    if request.headers.get("content-type", "").split(";")[0].strip().lower() != "application/json":
        raise HTTPException(status_code=415, detail="备份必须使用 application/json")
    try:
        length = int(request.headers.get("content-length", ""))
    except ValueError as exc:
        raise HTTPException(status_code=411, detail="备份缺少有效 Content-Length") from exc
    if length <= 0 or length > settings.max_backup_bytes:
        raise HTTPException(status_code=413, detail="备份超过大小限制")
    directory = settings.backup_dir / str(UUID(device["space_id"]))
    temporary: Path | None = None
    try:
        directory.mkdir(parents=True, exist_ok=True)
        _sync_directory(settings.backup_dir)
        fd, name = tempfile.mkstemp(prefix=f".{backup_id}-", suffix=".part", dir=directory)
        temporary = Path(name)
        digest, received = hashlib.sha256(), 0
        with os.fdopen(fd, "wb") as stream:
            async for chunk in request.stream():
                received += len(chunk)
                if received > length or received > settings.max_backup_bytes:
                    raise HTTPException(status_code=413, detail="备份超过声明长度或大小限制")
                digest.update(chunk)
                stream.write(chunk)
            stream.flush()
            os.fsync(stream.fileno())
        if received != length or digest.hexdigest() != x_content_sha256:
            raise HTTPException(status_code=422, detail="备份长度或 SHA-256 不匹配")
        count, snapshot_at, backup_schema = validate_snapshot(temporary)
        storage_key = f"{device['space_id']}/{backup_id}.json"
        final = _backup_file(storage_key)
        with connection() as conn:
            # Coordinate publication across worker processes without locking the space during upload.
            conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))", (storage_key,))
            active = conn.execute("""SELECT s.epoch FROM devices d JOIN sync_spaces s ON s.id=d.space_id
                                     WHERE d.space_id=%s AND d.id=%s AND d.revoked_at IS NULL
                                       AND d.token_hash=%s FOR SHARE OF d,s""",
                                  (device["space_id"], device["device_id"], hashlib.sha256(authorization.removeprefix("Bearer ").strip().encode()).hexdigest())).fetchone()
            if not active:
                raise HTTPException(status_code=401, detail="设备令牌已撤销")
            if str(active["epoch"]) != x_space_epoch:
                raise HTTPException(status_code=409, detail="家庭空间已重置，请重新对账")
            previous = conn.execute(SELECT + " WHERE b.space_id=%s AND b.id=%s", (device["space_id"], backup_id)).fetchone()
            if previous:
                if str(previous["device_id"]) != device["device_id"]:
                    raise HTTPException(status_code=404, detail="备份不存在")
                if previous["deleted_at"] is not None:
                    # Deleted snapshots never come back: not via retry, repair or same-id re-upload.
                    raise HTTPException(status_code=410, detail="此备份已删除，不能重新上传")
                if previous["sha256"].strip() != x_content_sha256:
                    raise HTTPException(status_code=409, detail="备份标识已用于不同内容")
                if not final.is_file() or final.stat().st_size != received or file_digest(final) != x_content_sha256:
                    # Repair a missing/corrupted file using this verified immutable upload.
                    os.replace(temporary, final)
                    _sync_directory(directory)
                return metadata(previous)
            if final.exists():
                if final.stat().st_size != received or file_digest(final) != x_content_sha256:
                    raise HTTPException(status_code=409, detail="备份标识存在不同内容的孤立文件")
            else:
                os.replace(temporary, final)
                _sync_directory(directory)
            conn.execute("""INSERT INTO ledger_backups(space_id,id,device_id,app_version,backup_schema,storage_key,sha256,
                              size_bytes,bill_count,snapshot_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                         (device["space_id"], backup_id, device["device_id"], x_app_version, backup_schema, storage_key,
                          x_content_sha256, received, count, snapshot_at))
            row = conn.execute(SELECT + " WHERE b.space_id=%s AND b.id=%s", (device["space_id"], backup_id)).fetchone()
            conn.commit()
            return metadata(row)
    except OSError as exc:
        raise HTTPException(status_code=507, detail="家庭备份磁盘写入失败，已有快照保留") from exc
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


@router.get("/v1/backups")
def list_backups(offset: int = Query(default=0, ge=0), limit: int = Query(default=50, ge=1, le=100),
                 device: dict = Depends(current_device)) -> dict:
    with connection() as conn:
        rows = conn.execute(SELECT + " WHERE b.space_id=%s AND b.device_id=%s AND b.deleted_at IS NULL"
                            " ORDER BY b.uploaded_at DESC,b.id DESC LIMIT %s OFFSET %s",
                            (device["space_id"], device["device_id"], limit + 1, offset)).fetchall()
    return {"protocolVersion": 1, "spaceId": device["space_id"], "epoch": device["epoch"],
            "items": [metadata(row) for row in rows[:limit]], "hasMore": len(rows) > limit,
            "nextOffset": offset + min(len(rows), limit)}


@router.get("/v1/backups/{backup_id}")
def download_backup(backup_id: UUID, device: dict = Depends(current_device)):
    with connection() as conn:
        row = conn.execute(SELECT + " WHERE b.space_id=%s AND b.id=%s AND b.device_id=%s AND b.deleted_at IS NULL",
                           (device["space_id"], backup_id, device["device_id"])).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="备份不存在")
    path = _backup_file(row["storage_key"])
    try:
        if path.stat().st_size != row["size_bytes"] or file_digest(path) != row["sha256"].strip():
            raise HTTPException(status_code=409, detail="备份文件损坏，请恢复家庭服务文件卷")
    except OSError as exc:
        raise HTTPException(status_code=409, detail="备份文件不可用，请恢复家庭服务文件卷") from exc
    return FileResponse(path, media_type="application/json", filename=f"MyMoney-{backup_id}.json",
                        headers={"X-Content-SHA256": row["sha256"].strip(), "Cache-Control": "no-store"})


def latest_backup_metadata(space_id: str, device_id: str) -> dict | None:
    """Newest live snapshot metadata for one source (used by backup-context)."""
    with connection() as conn:
        row = conn.execute(SELECT + " WHERE b.space_id=%s AND b.device_id=%s AND b.deleted_at IS NULL"
                           " ORDER BY b.uploaded_at DESC,b.id DESC LIMIT 1",
                           (space_id, device_id)).fetchone()
    return metadata(row) if row else None


@router.get("/v1/backups/{backup_id}/status")
def backup_status(backup_id: UUID, device: dict = Depends(current_device)):
    """Read-only restore-eligibility check used before a final restore confirm."""
    with connection() as conn:
        row = conn.execute(SELECT + " WHERE b.space_id=%s AND b.id=%s AND b.device_id=%s AND b.deleted_at IS NULL",
                           (device["space_id"], backup_id, device["device_id"])).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="备份不存在或已删除")
    return metadata(row)


class DeleteRequest(BaseModel):
    operationId: UUID
    backupIds: list[UUID] = Field(min_length=1, max_length=MAX_DELETE_BATCH)


def _recycle_result(rows: list[dict]) -> dict:
    """Summarize tombstoned rows against the current state of their files."""
    recycled_bytes = pending_bytes = 0
    for row in rows:
        path = _backup_file(row["storage_key"])
        gone = row["recycled_at"] is not None or not path.is_file()
        if gone:
            recycled_bytes += row["size_bytes"]
        else:
            pending_bytes += row["size_bytes"]
    return {"deletedCount": len(rows), "deletedBytes": recycled_bytes + pending_bytes,
            "recycledBytes": recycled_bytes, "pendingBytes": pending_bytes,
            "recyclePending": pending_bytes > 0}


def _recycle_files(rows: list[dict]) -> None:
    """Best-effort file removal for already-committed tombstones (idempotent).

    A disk error never fails the delete: the tombstone is already committed and
    the row stays pending until the startup sweep or a later retry clears it.
    """
    with connection() as conn:
        for row in rows:
            try:
                path = _backup_file(row["storage_key"])
                if not path.is_file():
                    continue
                path.unlink()
                _sync_directory(path.parent)
            except OSError:
                continue
            conn.execute("""UPDATE ledger_backups SET recycled_at=now()
                            WHERE space_id=%s AND id=%s AND deleted_at IS NOT NULL AND recycled_at IS NULL""",
                         (row["space_id"], row["id"]))
            conn.commit()


def recycle_pending_backups() -> dict:
    """Recovery sweep for tombstones whose file removal previously failed."""
    with connection() as conn:
        rows = conn.execute("""SELECT b.*, d.name AS device_name FROM ledger_backups b
                               JOIN devices d ON d.space_id=b.space_id AND d.id=b.device_id
                               WHERE b.deleted_at IS NOT NULL AND b.recycled_at IS NULL
                               ORDER BY b.deleted_at""").fetchall()
    pending = [row for row in rows if _backup_file(row["storage_key"]).is_file()]
    _recycle_files(pending)
    with connection() as conn:
        remaining = conn.execute("SELECT count(*) AS n, coalesce(sum(size_bytes),0) AS bytes"
                                 " FROM ledger_backups WHERE deleted_at IS NOT NULL AND recycled_at IS NULL").fetchone()
    return {"swept": len(pending), "pendingCount": remaining["n"], "pendingBytes": remaining["bytes"]}


@router.post("/v1/backups/delete")
def delete_backups(body: DeleteRequest, device: dict = Depends(current_device),
                   authorization: str = Header(default="")) -> dict:
    ids = sorted({str(item) for item in body.backupIds})
    if not 1 <= len(ids) <= MAX_DELETE_BATCH:
        raise HTTPException(status_code=422, detail=f"单次最多删除{MAX_DELETE_BATCH}份备份")
    request_hash = hashlib.sha256(json.dumps(
        {"operationId": str(body.operationId), "sourceId": device["device_id"], "backupIds": ids},
        sort_keys=True).encode()).hexdigest()
    with connection() as conn:
        # Same per-storage-key locks as upload, taken in sorted order, so a
        # concurrent upload/repair of the same ids cannot interleave with us.
        for backup_id in ids:
            conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                         (f"{device['space_id']}/{backup_id}.json",))
        active = conn.execute("""SELECT s.epoch FROM devices d JOIN sync_spaces s ON s.id=d.space_id
                                 WHERE d.space_id=%s AND d.id=%s AND d.revoked_at IS NULL
                                   AND d.token_hash=%s FOR SHARE OF d,s""",
                              (device["space_id"], device["device_id"],
                               hashlib.sha256(authorization.removeprefix("Bearer ").strip().encode()).hexdigest())).fetchone()
        if not active:
            raise HTTPException(status_code=401, detail="设备令牌已撤销")
        previous = conn.execute("""SELECT * FROM backup_delete_operations
                                   WHERE space_id=%s AND source_id=%s AND operation_id=%s""",
                                 (device["space_id"], device["device_id"], body.operationId)).fetchone()
        if previous:
            if previous["request_hash"] != request_hash:
                raise HTTPException(status_code=409, detail="该操作已用于不同的删除请求")
            rows = conn.execute(SELECT + " WHERE b.space_id=%s AND b.id = ANY(%s)",
                                (device["space_id"], [UUID(item) for item in ids])).fetchall()
            result = _recycle_result(rows)
            result["operationId"] = str(body.operationId)
            return result
        rows = conn.execute(SELECT + """ WHERE b.space_id=%s AND b.id = ANY(%s)
                                          ORDER BY b.uploaded_at DESC, b.id DESC FOR UPDATE""",
                             (device["space_id"], [UUID(item) for item in ids])).fetchall()
        by_id = {str(row["id"]): row for row in rows}
        for backup_id in ids:
            row = by_id.get(backup_id)
            if row is None or str(row["device_id"]) != device["device_id"]:
                # Foreign or missing ids are indistinguishable; the whole batch is rejected.
                raise HTTPException(status_code=404, detail="备份不存在或已删除")
            if row["deleted_at"] is not None:
                raise HTTPException(status_code=409, detail="所选备份已被删除，请刷新后重试")
        total_bytes = sum(row["size_bytes"] for row in rows)
        conn.execute("""UPDATE ledger_backups SET deleted_at=now()
                        WHERE space_id=%s AND id = ANY(%s) AND deleted_at IS NULL""",
                     (device["space_id"], [UUID(item) for item in ids]))
        conn.execute("""INSERT INTO backup_delete_operations
                        (space_id,source_id,operation_id,request_hash,deleted_backup_ids,deleted_bytes)
                        VALUES (%s,%s,%s,%s,%s,%s)""",
                     (device["space_id"], device["device_id"], body.operationId, request_hash,
                      [UUID(item) for item in ids], total_bytes))
        conn.commit()
    # File removal happens only after the tombstone transaction committed.
    _recycle_files(rows)
    result = _recycle_result(rows)
    result["operationId"] = str(body.operationId)
    return result


def audit_files() -> dict:
    """Read-only maintenance inventory. Never automatically delete orphan/partial files."""
    root = get_settings().backup_dir
    with connection() as conn:
        rows = conn.execute("SELECT storage_key,size_bytes,sha256,deleted_at,recycled_at FROM ledger_backups").fetchall()
    live = [row for row in rows if row["deleted_at"] is None]
    tombstoned = [row for row in rows if row["deleted_at"] is not None]
    indexed = {row["storage_key"] for row in live}
    tombstone_keys = {row["storage_key"] for row in tombstoned}
    complete = {str(path.relative_to(root)) for path in root.glob("*/*.json")}
    missing, corrupt, pending = [], [], []
    for row in live:
        path = _backup_file(row["storage_key"])
        if not path.is_file():
            missing.append(row["storage_key"])
        elif path.stat().st_size != row["size_bytes"] or file_digest(path) != row["sha256"].strip():
            corrupt.append(row["storage_key"])
    for row in tombstoned:
        if row["recycled_at"] is None and _backup_file(row["storage_key"]).is_file():
            pending.append(row["storage_key"])
    return {"indexedCount": len(live), "orphanFiles": sorted(complete - indexed - tombstone_keys),
            "partialFiles": sorted(str(path.relative_to(root)) for path in root.glob("*/*.part")),
            "missingFiles": sorted(missing), "corruptFiles": sorted(corrupt),
            "deletedCount": len(tombstoned), "pendingRecycleFiles": sorted(pending)}
