"""Immutable JSON8 backup files; only committed indexes are downloadable."""
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

from .auth import current_device
from .config import get_settings
from .db import connection

router = APIRouter()


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_snapshot(path: Path) -> tuple[int, datetime]:
    """Validate the portable envelope and actual rows; Android validates all relations."""
    try:
        with path.open("r", encoding="utf-8") as stream:
            data = json.load(stream, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        if not isinstance(data, dict) or data.get("kind") != "MyMoneyBackup" or type(data.get("format")) is not int or data["format"] != 8:
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
        for key in ("history", "imports"):
            if data.get(key) is not None and (not isinstance(data[key], list) or any(not isinstance(row, dict) for row in data[key])):
                raise ValueError()
        for key in ("assets", "capture", "ledger", "settings"):
            if data.get(key) is not None and not isinstance(data[key], dict):
                raise ValueError()
        return len(bills), snapshot_at
    except (ValueError, TypeError, UnicodeError, RecursionError, OverflowError, OSError) as exc:
        raise HTTPException(status_code=422, detail="备份不是有效的 JSON8 快照") from exc


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
) -> dict:
    settings = get_settings()
    if x_space_epoch != device["epoch"]:
        raise HTTPException(status_code=409, detail="家庭空间已重置，请重新对账")
    if not re.fullmatch(r"[0-9a-f]{64}", x_content_sha256) or not 1 <= len(x_app_version) <= 80:
        raise HTTPException(status_code=422, detail="备份摘要或应用版本无效")
    if request.headers.get("content-encoding", "identity").lower() != "identity":
        raise HTTPException(status_code=415, detail="仅接受未压缩 JSON8 快照")
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
        count, snapshot_at = validate_snapshot(temporary)
        storage_key = f"{device['space_id']}/{backup_id}.json"
        final = _backup_file(storage_key)
        with connection() as conn:
            # Coordinate publication across worker processes without locking the space during upload.
            conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))", (storage_key,))
            active = conn.execute("""SELECT s.epoch FROM devices d JOIN sync_spaces s ON s.id=d.space_id
                                     WHERE d.space_id=%s AND d.id=%s AND d.revoked_at IS NULL FOR SHARE OF d,s""",
                                  (device["space_id"], device["device_id"])).fetchone()
            if not active:
                raise HTTPException(status_code=401, detail="设备令牌已撤销")
            if str(active["epoch"]) != x_space_epoch:
                raise HTTPException(status_code=409, detail="家庭空间已重置，请重新对账")
            previous = conn.execute(SELECT + " WHERE b.space_id=%s AND b.id=%s", (device["space_id"], backup_id)).fetchone()
            if previous:
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
                              size_bytes,bill_count,snapshot_at) VALUES (%s,%s,%s,%s,8,%s,%s,%s,%s,%s)""",
                         (device["space_id"], backup_id, device["device_id"], x_app_version, storage_key,
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
        rows = conn.execute(SELECT + " WHERE b.space_id=%s ORDER BY b.snapshot_at DESC,b.id DESC LIMIT %s OFFSET %s",
                            (device["space_id"], limit + 1, offset)).fetchall()
    return {"protocolVersion": 1, "spaceId": device["space_id"], "epoch": device["epoch"],
            "items": [metadata(row) for row in rows[:limit]], "hasMore": len(rows) > limit,
            "nextOffset": offset + min(len(rows), limit)}


@router.get("/v1/backups/{backup_id}")
def download_backup(backup_id: UUID, device: dict = Depends(current_device)):
    with connection() as conn:
        row = conn.execute(SELECT + " WHERE b.space_id=%s AND b.id=%s", (device["space_id"], backup_id)).fetchone()
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


def audit_files() -> dict:
    """Read-only maintenance inventory. Never automatically delete orphan/partial files."""
    root = get_settings().backup_dir
    with connection() as conn:
        rows = conn.execute("SELECT storage_key,size_bytes,sha256 FROM ledger_backups").fetchall()
    indexed = {row["storage_key"] for row in rows}
    complete = {str(path.relative_to(root)) for path in root.glob("*/*.json")}
    missing, corrupt = [], []
    for row in rows:
        path = _backup_file(row["storage_key"])
        if not path.is_file():
            missing.append(row["storage_key"])
        elif path.stat().st_size != row["size_bytes"] or file_digest(path) != row["sha256"].strip():
            corrupt.append(row["storage_key"])
    return {"indexedCount": len(rows), "orphanFiles": sorted(complete - indexed),
            "partialFiles": sorted(str(path.relative_to(root)) for path in root.glob("*/*.part")),
            "missingFiles": sorted(missing), "corruptFiles": sorted(corrupt), "deletedCount": 0}
