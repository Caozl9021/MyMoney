"""Stable personal backup sources. IDs identify; bearer secrets authorize.

No plaintext connection codes, grants or device credentials are persisted. A grant
is deterministically derived from the high-entropy connection code, so a lost
response can be retried by the same installation without issuing another device.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import secrets
from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, HTTPException, Query
from pydantic import BaseModel, Field

from .auth import current_device, hash_token
from .db import connection
from .service import ensure_space

router = APIRouter()


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _bearer(authorization: str) -> str:
    if not authorization.startswith("Bearer ") or not authorization[7:].strip():
        raise HTTPException(401, "请重新连接")
    return authorization[7:].strip()


def _identity(row: dict) -> dict:
    return {"deviceId": str(row["id"]), "spaceId": str(row["space_id"]),
            "epoch": str(row["epoch"]), "credentialGeneration": row["credential_generation"],
            "name": row["name"]}


def issue_connection_code(conn, ttl_seconds: int = 300) -> dict:
    """Issue one private-space enrollment authorization, valid at most 15 minutes.

    Returns the short code the App pastes plus an opaque invitation id and its
    server-side timing, so the admin page can render a QR and poll lifecycle status
    without ever seeing the code again through a status endpoint.
    """
    if not 30 <= ttl_seconds <= 900:
        raise HTTPException(422, "连接码有效期须为30至900秒")
    conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))", ("device-enrollment-ensure-space",))
    space = ensure_space(conn)
    code = secrets.token_urlsafe(18)
    # invitation_id is a public opaque handle; it is not derived from and cannot restore
    # the code (only code_hash/grant_hash are stored).
    invitation_id = uuid4()
    expires_at = _now() + timedelta(seconds=ttl_seconds)
    conn.execute("""INSERT INTO device_connection_codes(code_hash,space_id,epoch,expires_at,invitation_id)
                    VALUES (%s,%s,%s,%s,%s)""",
                 (hash_token(code), space["id"], space["epoch"], expires_at, invitation_id))
    conn.commit()
    return {"code": code, "invitationId": str(invitation_id),
            "expiresAt": expires_at.isoformat(), "expiresInSeconds": ttl_seconds,
            "serverTime": _now().isoformat()}


def _invitation_row(conn, invitation_id, *, lock: bool = False) -> dict:
    row = conn.execute("""SELECT * FROM device_connection_codes WHERE invitation_id=%s"""
                       + (" FOR UPDATE" if lock else ""), (invitation_id,)).fetchone()
    if not row:
        raise HTTPException(404, "邀请不存在")
    return row


def _invitation_state(row: dict) -> str:
    """Derive lifecycle state from the persisted authorization row.

    Values: expired | cancelled | completed | authorized | pending.
    - completed: the admin will confirm via enrollment operation existence — handled by
      caller only when it looks it up; here we report `authorized` once consumed because
      the grant holder has claimed it. `completed` is set by the caller after checking
      device_enrollment_operations. Kept conservative: no fabricated success.
    """
    if row.get("cancelled_at") is not None:
        return "cancelled"
    if row["expires_at"] <= _now():
        return "expired"
    if row["consumed_at"] is not None:
        # The grant is claimed; whether it has been registered is decided by complete().
        return "authorized"
    return "pending"


def invitation_status(conn, invitation_id) -> dict:
    row = _invitation_row(conn, invitation_id)
    completed = None
    if row["grant_hash"] is not None:
        completed = conn.execute(
            "SELECT source_id FROM device_enrollment_operations WHERE grant_hash=%s",
            (row["grant_hash"],)).fetchone()
    state = _invitation_state(row)
    if state == "authorized" and completed is not None:
        state = "completed"
    return {
        "invitationId": str(row["invitation_id"]),
        "status": state,
        "expiresAt": row["expires_at"].isoformat(),
        "serverTime": _now().isoformat(),
        "deviceId": str(completed["source_id"]) if completed is not None else None,
    }


def cancel_invitation(conn, invitation_id) -> dict:
    """Cancel only the not-yet-completed business authorization for this invitation.

    - already completed registration -> refuse, return completed (never revoke device)
    - already cancelled/expired -> idempotent, return that state
    - cancelled sets cancelled_at so authorize() refuses a claimed-but-unsubmitted grant
    """
    conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                 (f"device-enrollment-cancel:{invitation_id}",))
    row = _invitation_row(conn, invitation_id, lock=True)
    if row["grant_hash"] is not None:
        completed = conn.execute(
            "SELECT source_id FROM device_enrollment_operations WHERE grant_hash=%s",
            (row["grant_hash"],)).fetchone()
        if completed is not None:
            conn.commit()
            return {"invitationId": str(invitation_id), "status": "completed",
                    "deviceId": str(completed["source_id"])}
    if row["cancelled_at"] is not None:
        conn.commit()
        return {"invitationId": str(invitation_id), "status": "cancelled"}
    if row["consumed_at"] is not None and row["expires_at"] <= _now():
        conn.commit()
        return {"invitationId": str(invitation_id), "status": "expired"}
    conn.execute("UPDATE device_connection_codes SET cancelled_at=now() WHERE invitation_id=%s",
                 (invitation_id,))
    conn.commit()
    return {"invitationId": str(invitation_id), "status": "cancelled"}


class AuthorizeRequest(BaseModel):
    code: str = Field(min_length=1, max_length=32)
    installationId: UUID
    operationId: UUID


class CompleteRequest(BaseModel):
    installationId: UUID
    operationId: UUID
    credential: str = Field(min_length=43, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")
    deviceName: str = Field(min_length=1, max_length=80)
    sourceId: UUID | None = None
    expectedGeneration: int | None = Field(default=None, ge=1)


class BindRequest(BaseModel):
    installationId: UUID


class RenameRequest(BaseModel):
    name: str = Field(min_length=1, max_length=80)


@router.get("/v1/backup-capabilities")
def capabilities() -> dict:
    return {"deviceIdentityVersion": 1, "ownBackupsOnly": True,
            "deleteBackups": True, "backupContext": True}


@router.get("/v1/backup-context")
def backup_context(installationId: UUID | None = Query(default=None),
                   device: dict = Depends(current_device),
                   authorization: str = Header(default="")) -> dict:
    """One round trip for what the home page needs: identity re-verification,
    capability negotiation and the newest live backup metadata.

    Read-only for the backup index; binding stays in bind-installation so a
    GET never mutates identity.
    """
    with connection() as conn:
        row = _authenticated_row(conn, device, authorization)
        # Latest live snapshot only; deleted tombstones stay metadata, never content.
        from .backups import latest_backup_metadata
    latest = latest_backup_metadata(device["space_id"], device["device_id"])
    with connection() as conn:
        conn.execute("UPDATE devices SET last_seen_at=now() WHERE space_id=%s AND id=%s",
                     (device["space_id"], device["device_id"]))
        conn.commit()
    bound = row["installation_id"]
    return {"identity": _identity(row),
            "capabilities": {"deviceIdentityVersion": 1, "ownBackupsOnly": True,
                             "deleteBackups": True, "backupContext": True},
            "installationId": str(bound) if bound else None,
            "installationMatches": str(bound) == str(installationId) if (bound and installationId) else None,
            "latestBackup": latest,
            "protocolVersion": 1}


@router.post("/v1/device-enrollment/authorize")
def authorize(body: AuthorizeRequest) -> dict:
    code = body.code.strip()
    grant = hmac.new(code.encode(),
                     f"mymoney-enrollment-v1:{body.installationId}:{body.operationId}".encode(),
                     hashlib.sha256).hexdigest()
    with connection() as conn:
        row = conn.execute("""SELECT c.*, s.epoch AS current_epoch FROM device_connection_codes c
                              JOIN sync_spaces s ON s.id=c.space_id
                              WHERE c.code_hash=%s FOR UPDATE OF c""", (hash_token(code),)).fetchone()
        if not row or row["expires_at"] <= _now() or row["epoch"] != row["current_epoch"]:
            raise HTTPException(401, "连接码无效或已过期，请重新生成")
        if row.get("cancelled_at") is not None:
            raise HTTPException(401, "该连接邀请已被取消，请重新生成")
        if row["consumed_at"] is not None:
            if row["installation_id"] != body.installationId or row["operation_id"] != body.operationId:
                raise HTTPException(401, "连接码已使用，请重新生成")
            if row["grant_expires_at"] <= _now():
                raise HTTPException(401, "连接授权已过期，请重新生成")
        else:
            conn.execute("""UPDATE device_connection_codes SET consumed_at=now(),installation_id=%s,
                            operation_id=%s,grant_hash=%s,grant_expires_at=%s WHERE code_hash=%s""",
                         (body.installationId, body.operationId, hash_token(grant),
                          _now() + timedelta(minutes=10), hash_token(code)))
        conn.commit()
        return {"grant": grant, "spaceId": str(row["space_id"]), "epoch": str(row["epoch"])}


def _grant(conn, authorization: str, *, lock: bool = False) -> dict:
    row = conn.execute("""SELECT c.*, s.epoch AS current_epoch FROM device_connection_codes c
                          JOIN sync_spaces s ON s.id=c.space_id WHERE c.grant_hash=%s"""
                       + (" FOR UPDATE OF c" if lock else ""),
                       (hash_token(_bearer(authorization)),)).fetchone()
    if not row or row["grant_expires_at"] <= _now() or row["epoch"] != row["current_epoch"]:
        raise HTTPException(401, "连接授权无效或已过期，请重新生成连接码")
    if row.get("cancelled_at") is not None:
        raise HTTPException(401, "该连接邀请已被取消，请重新生成")
    return row


@router.get("/v1/device-enrollment/sources")
def sources(authorization: str = Header(default="")) -> dict:
    with connection() as conn:
        grant = _grant(conn, authorization)
        completed = conn.execute("SELECT 1 FROM device_enrollment_operations WHERE grant_hash=%s",
                                 (grant["grant_hash"],)).fetchone()
        if completed:
            raise HTTPException(409, "该连接授权已完成登记")
        rows = conn.execute("""SELECT d.id,d.name,d.last_seen_at,d.credential_generation,
                                      d.archived_at,d.revoked_at,max(b.uploaded_at) AS last_backup_at
                               FROM devices d LEFT JOIN ledger_backups b
                                 ON b.space_id=d.space_id AND b.device_id=d.id AND b.deleted_at IS NULL
                               WHERE d.space_id=%s GROUP BY d.space_id,d.id
                               ORDER BY d.archived_at NULLS FIRST,d.created_at DESC,d.id""",
                            (grant["space_id"],)).fetchall()
        return {"items": [{"deviceId": str(r["id"]), "name": r["name"],
                           "lastSeenAt": r["last_seen_at"].isoformat() if r["last_seen_at"] else None,
                           "lastBackupAt": r["last_backup_at"].isoformat() if r["last_backup_at"] else None,
                           "credentialGeneration": r["credential_generation"],
                           "archived": r["archived_at"] is not None, "revoked": r["revoked_at"] is not None}
                          for r in rows]}


@router.post("/v1/device-enrollment/complete")
def complete(body: CompleteRequest, authorization: str = Header(default="")) -> dict:
    credential_hash = hash_token(body.credential)
    normalized = {"installationId": str(body.installationId), "operationId": str(body.operationId),
                  "credentialHash": credential_hash, "deviceName": body.deviceName.strip(),
                  "sourceId": str(body.sourceId) if body.sourceId else None,
                  "expectedGeneration": body.expectedGeneration}
    if not normalized["deviceName"]:
        raise HTTPException(422, "设备名称不能为空")
    request_hash = hashlib.sha256(json.dumps(normalized, sort_keys=True).encode()).hexdigest()
    with connection() as conn:
        grant = _grant(conn, authorization)
        # One space-wide enrollment lock also coordinates distinct grants claiming
        # the same installation/source, without depending on a single worker.
        conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                     (f"device-enrollment:{grant['space_id']}",))
        grant = _grant(conn, authorization, lock=True)
        if grant["installation_id"] != body.installationId or grant["operation_id"] != body.operationId:
            raise HTTPException(403, "连接授权不属于此安装或操作")
        previous = conn.execute("SELECT * FROM device_enrollment_operations WHERE grant_hash=%s",
                                (grant["grant_hash"],)).fetchone()
        if previous:
            if previous["request_hash"] != request_hash:
                raise HTTPException(409, "该连接授权已用于不同操作")
            row = conn.execute("""SELECT d.*,s.epoch FROM devices d JOIN sync_spaces s ON s.id=d.space_id
                                  WHERE d.space_id=%s AND d.id=%s""",
                               (grant["space_id"], previous["source_id"])).fetchone()
            if (not row or row["revoked_at"] is not None
                    or row["credential_generation"] != previous["result_generation"]
                    or row["token_hash"] != credential_hash or row["installation_id"] != body.installationId):
                raise HTTPException(409, "设备身份已变化，请重新连接；旧操作不能恢复权限")
            conn.commit()
            return _identity(row)
        credential_owner = conn.execute("SELECT space_id,id FROM devices WHERE token_hash=%s",
                                        (credential_hash,)).fetchone()
        if credential_owner and (credential_owner["space_id"] != grant["space_id"]
                                 or (body.sourceId is not None and credential_owner["id"] != body.sourceId)):
            raise HTTPException(409, "新凭证已被其他设备使用")
        installed = conn.execute("SELECT * FROM devices WHERE space_id=%s AND installation_id=%s FOR UPDATE",
                                 (grant["space_id"], body.installationId)).fetchone()
        if body.sourceId is None:
            if body.expectedGeneration is not None:
                raise HTTPException(422, "添加新设备不需要原设备版本")
            if installed:
                # An ID is not a credential. A retry with the same saved secret
                # may recover its own source; a UUID alone never takes it over.
                if installed["token_hash"] != credential_hash or installed["revoked_at"] is not None:
                    raise HTTPException(409, "此安装已登记，请验证原凭证或明确接替原设备")
                source_id = installed["id"]
            else:
                if credential_owner:
                    raise HTTPException(409, "新凭证已被其他设备使用")
                source_id = uuid4()
                conn.execute("""INSERT INTO devices(space_id,id,name,token_hash,installation_id,last_seen_at)
                                VALUES (%s,%s,%s,%s,%s,now())""",
                             (grant["space_id"], source_id, normalized["deviceName"],
                              credential_hash, body.installationId))
        else:
            if body.expectedGeneration is None:
                raise HTTPException(422, "接替设备必须确认原设备版本")
            row = conn.execute("SELECT * FROM devices WHERE space_id=%s AND id=%s FOR UPDATE",
                               (grant["space_id"], body.sourceId)).fetchone()
            if not row:
                raise HTTPException(404, "原设备不存在")
            if row["token_hash"] == credential_hash:
                raise HTTPException(409, "接替设备必须使用新的连接凭证")
            if row["credential_generation"] != body.expectedGeneration:
                raise HTTPException(409, "原设备状态已变化，请刷新后重新确认")
            if installed and installed["id"] != body.sourceId:
                raise HTTPException(409, "此安装已属于另一设备，请先退出原连接")
            source_id = body.sourceId
            conn.execute("""UPDATE devices SET installation_id=%s,token_hash=%s,name=%s,
                              credential_generation=credential_generation+1,revoked_at=NULL,
                              archived_at=NULL,last_seen_at=now() WHERE space_id=%s AND id=%s""",
                         (body.installationId, credential_hash, normalized["deviceName"], grant["space_id"], source_id))
        row = conn.execute("""SELECT d.*,s.epoch FROM devices d JOIN sync_spaces s ON s.id=d.space_id
                              WHERE d.space_id=%s AND d.id=%s""", (grant["space_id"], source_id)).fetchone()
        conn.execute("""INSERT INTO device_enrollment_operations
                        (grant_hash,space_id,source_id,operation_id,request_hash,credential_hash,result_generation)
                        VALUES (%s,%s,%s,%s,%s,%s,%s)""",
                     (grant["grant_hash"], grant["space_id"], source_id, body.operationId,
                      request_hash, credential_hash, row["credential_generation"]))
        conn.commit()
        return _identity(row)


def _authenticated_row(conn, device: dict, authorization: str, *, lock: bool = False) -> dict:
    row = conn.execute("""SELECT d.*,s.epoch FROM devices d JOIN sync_spaces s ON s.id=d.space_id
                          WHERE d.space_id=%s AND d.id=%s AND d.revoked_at IS NULL AND d.token_hash=%s"""
                       + (" FOR UPDATE OF d" if lock else ""),
                       (device["space_id"], device["device_id"], hash_token(_bearer(authorization)))).fetchone()
    if not row:
        raise HTTPException(401, "设备凭证已失效，请重新连接")
    return row


@router.get("/v1/devices/self")
def self_device(device: dict = Depends(current_device), authorization: str = Header(default="")) -> dict:
    with connection() as conn:
        row = _authenticated_row(conn, device, authorization)
        conn.execute("UPDATE devices SET last_seen_at=now() WHERE space_id=%s AND id=%s",
                     (device["space_id"], device["device_id"]))
        conn.commit()
        return _identity(row)


@router.post("/v1/devices/self/bind-installation")
def bind_installation(body: BindRequest, device: dict = Depends(current_device),
                      authorization: str = Header(default="")) -> dict:
    with connection() as conn:
        conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                     (f"device-enrollment:{device['space_id']}",))
        row = _authenticated_row(conn, device, authorization, lock=True)
        if row["installation_id"] is not None and row["installation_id"] != body.installationId:
            raise HTTPException(409, "此来源已绑定其他安装，请通过连接码明确接替")
        occupied = conn.execute("SELECT id FROM devices WHERE space_id=%s AND installation_id=%s",
                                (device["space_id"], body.installationId)).fetchone()
        if occupied and str(occupied["id"]) != device["device_id"]:
            raise HTTPException(409, "此安装已绑定其他来源")
        conn.execute("UPDATE devices SET installation_id=%s,last_seen_at=now() WHERE space_id=%s AND id=%s",
                     (body.installationId, device["space_id"], device["device_id"]))
        conn.commit()
        return _identity(row)


@router.post("/v1/devices/self/rename")
def rename(body: RenameRequest, device: dict = Depends(current_device),
           authorization: str = Header(default="")) -> dict:
    if not body.name.strip():
        raise HTTPException(422, "设备名称不能为空")
    with connection() as conn:
        _authenticated_row(conn, device, authorization, lock=True)
        conn.execute("UPDATE devices SET name=%s WHERE space_id=%s AND id=%s",
                     (body.name.strip(), device["space_id"], device["device_id"]))
        row = _authenticated_row(conn, device, authorization)
        conn.commit()
        return _identity(row)
