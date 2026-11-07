from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse

from . import service
from .backups import recycle_pending_backups
from .backups import router as backups_router
from .device_enrollment import router as enrollment_router
from .backup_admin import router as backup_admin_router
from .ledger_api import router as ledger_router
from .auth import current_device, hash_token
from .config import get_settings
from .db import connection
from .models import AckRequest, DeviceRevokeResponse, PairRequest, PairResponse, PushItem, PushRequest, PullResponse
from .pairing import PAIRING


@asynccontextmanager
async def lifespan(app: FastAPI):
    if get_settings().pairing_code:
        PAIRING.issue()
    # Deleted backups are tombstoned before their files are unlinked; sweep any
    # recycle that was interrupted (crash, disk error) before serving requests.
    try:
        summary = recycle_pending_backups()
        if summary["pendingCount"]:
            logging.getLogger(__name__).warning("backup recycle still pending: %s", summary)
    except Exception:  # pragma: no cover - startup must not fail on housekeeping
        logging.getLogger(__name__).exception("backup recycle sweep failed")
    yield


app = FastAPI(title="My Money Home Sync", version="0.2.0", lifespan=lifespan)
app.include_router(backups_router)
app.include_router(enrollment_router)
app.include_router(backup_admin_router)
app.include_router(ledger_router)


@app.middleware("http")
async def private_api_no_store(request: Request, call_next):
    response = await call_next(request)
    if request.url.path.startswith(("/v1/", "/admin")):
        response.headers["Cache-Control"] = "no-store"
        response.headers["Pragma"] = "no-cache"
    return response


@app.get("/health")
def health() -> dict:
    with connection() as conn:
        conn.execute("SELECT 1")
    return {"ok": True, "protocolVersion": get_settings().protocol_version}


@app.post("/v1/admin/pair-code")
def admin_pair_code(x_admin_token: str = Header(default="")) -> dict:
    settings = get_settings()
    if not settings.admin_token or x_admin_token != settings.admin_token:
        raise HTTPException(status_code=403, detail="管理令牌无效")
    code = PAIRING.issue()
    expires = 0 if settings.pairing_ttl_seconds <= 0 else settings.pairing_ttl_seconds
    return {"code": code, "expiresInSeconds": expires}


@app.post("/v1/pair", response_model=PairResponse)
def pair(body: PairRequest) -> PairResponse:
    with connection() as conn:
        result = service.pair_device(conn, body.code, body.deviceName)
    return PairResponse(**result)


@app.get("/v1/sync/snapshot")
def sync_snapshot(device: dict = Depends(current_device)) -> dict:
    with connection() as conn:
        space = conn.execute("SELECT * FROM sync_spaces WHERE id=%s", (device["space_id"],)).fetchone()
        if not space:
            raise HTTPException(status_code=409, detail="同步空间不存在")
        return service.snapshot(conn, space)


@app.post("/v1/sync/push")
async def sync_push(request: Request, body: PushRequest, device: dict = Depends(current_device)) -> dict:
    settings = get_settings()
    length = request.headers.get("content-length")
    if length and int(length) > settings.max_push_bytes:
        raise HTTPException(status_code=413, detail="同步请求超过大小限制")
    if body.protocolVersion != settings.protocol_version:
        raise HTTPException(status_code=409, detail="协议版本不兼容")
    if body.spaceId != device["space_id"]:
        raise HTTPException(status_code=403, detail="不能写入其他同步空间")
    with connection() as conn:
        space = conn.execute("SELECT * FROM sync_spaces WHERE id=%s", (body.spaceId,)).fetchone()
        if not space:
            raise HTTPException(status_code=409, detail="同步空间不存在")
        results = [service.push_item(conn, device, space, item) for item in body.items]
    return {"protocolVersion": settings.protocol_version, "items": results}


@app.get("/v1/sync/pull", response_model=PullResponse)
def sync_pull(
    after: int = Query(default=0, ge=0),
    limit: int = Query(default=100, ge=1, le=100),
    device: dict = Depends(current_device),
) -> PullResponse:
    with connection() as conn:
        space = conn.execute("SELECT * FROM sync_spaces WHERE id=%s", (device["space_id"],)).fetchone()
        if not space:
            raise HTTPException(status_code=409, detail="同步空间不存在")
        return PullResponse(**service.pull(conn, space, after, limit))


@app.post("/v1/sync/ack")
def sync_ack(body: AckRequest, device: dict = Depends(current_device)) -> dict:
    with connection() as conn:
        service.ack(conn, device, body.throughRevision, body.epoch)
    return {"ok": True}


@app.post("/v1/device/revoke", response_model=DeviceRevokeResponse)
def revoke_device(device: dict = Depends(current_device), authorization: str = Header(default="")) -> DeviceRevokeResponse:
    with connection() as conn:
        conn.execute(
            "UPDATE devices SET revoked_at=now() WHERE space_id=%s AND id=%s AND token_hash=%s",
            (device["space_id"], device["device_id"], hash_token(authorization.removeprefix("Bearer ").strip())),
        )
        conn.commit()
    return DeviceRevokeResponse(revoked=True)


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    return JSONResponse(status_code=exc.status_code, content={"error": exc.detail})
