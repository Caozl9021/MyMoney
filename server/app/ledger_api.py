from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, Request

from . import ledger_service
from .auth import current_device
from .config import get_settings
from .db import connection
from .models import AckRequest, LedgerPullResponse, LedgerPushRequest

router = APIRouter(prefix="/v1/ledger")


def _parse_ledger_id(value: str) -> str:
    try:
        return str(uuid.UUID(value))
    except (ValueError, AttributeError, TypeError):
        raise HTTPException(status_code=422, detail="账本标识无效")


def _space(conn, device: dict) -> dict:
    space = conn.execute("SELECT * FROM sync_spaces WHERE id=%s", (device["space_id"],)).fetchone()
    if not space:
        raise HTTPException(status_code=409, detail="同步空间不存在")
    return space


@router.get("/snapshot")
def ledger_snapshot(ledgerId: str = Query(min_length=1), device: dict = Depends(current_device)) -> dict:
    ledger_id = _parse_ledger_id(ledgerId)
    with connection() as conn:
        space = _space(conn, device)
        return ledger_service.snapshot(conn, space, ledger_id)


@router.post("/push")
async def ledger_push(body: LedgerPushRequest, request: Request, device: dict = Depends(current_device)) -> dict:
    settings = get_settings()
    length = request.headers.get("content-length")
    if length and int(length) > settings.max_push_bytes:
        raise HTTPException(status_code=413, detail="同步请求超过大小限制")
    if body.protocolVersion != settings.protocol_version:
        raise HTTPException(status_code=409, detail="协议版本不兼容")
    if body.spaceId != device["space_id"]:
        raise HTTPException(status_code=403, detail="不能写入其他同步空间")
    ledger_id = _parse_ledger_id(body.ledgerId)
    with connection() as conn:
        space = _space(conn, device)
        results = [ledger_service.push_item(conn, device, space, ledger_id, item) for item in body.items]
    return {"protocolVersion": settings.protocol_version, "items": results}


@router.get("/pull", response_model=LedgerPullResponse)
def ledger_pull(
    ledgerId: str = Query(min_length=1),
    after: int = Query(default=0, ge=0),
    limit: int = Query(default=100, ge=1, le=100),
    device: dict = Depends(current_device),
) -> LedgerPullResponse:
    ledger_id = _parse_ledger_id(ledgerId)
    with connection() as conn:
        space = _space(conn, device)
        return LedgerPullResponse(**ledger_service.pull(conn, space, ledger_id, after, limit))


@router.post("/ack")
def ledger_ack(body: AckRequest, ledgerId: str = Query(min_length=1), device: dict = Depends(current_device)) -> dict:
    ledger_id = _parse_ledger_id(ledgerId)
    with connection() as conn:
        space = _space(conn, device)
        ledger_service.ack(conn, device, space, ledger_id, body.throughRevision, body.epoch)
    return {"ok": True}


@router.get("/import/sources")
def ledger_import_sources(
    device: dict = Depends(current_device),
) -> dict:
    with connection() as conn:
        space = _space(conn, device)
        return ledger_service.list_import_sources(conn, device, space)


@router.get("/import/source")
def ledger_import_source(
    sourceDeviceId: str = Query(min_length=1),
    sourceLedgerId: str = Query(min_length=1),
    device: dict = Depends(current_device),
) -> dict:
    source_device_id = _parse_ledger_id(sourceDeviceId)
    source_ledger_id = _parse_ledger_id(sourceLedgerId)
    with connection() as conn:
        space = _space(conn, device)
        return ledger_service.import_source_snapshot(
            conn, device, space, source_device_id, source_ledger_id
        )
