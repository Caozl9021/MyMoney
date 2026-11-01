from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field


class PairRequest(BaseModel):
    code: str = Field(min_length=1, max_length=32)
    deviceName: str = Field(min_length=1, max_length=80)


class PairResponse(BaseModel):
    protocolVersion: int = 1
    spaceId: str
    epoch: str
    deviceId: str
    token: str


class PushItem(BaseModel):
    operationId: str = Field(min_length=16, max_length=96)
    epoch: str
    itemId: str = Field(min_length=1, max_length=128)
    baseRevision: int = Field(ge=0)
    operation: Literal["put", "delete", "restore"]
    kind: Literal["recognition_rule", "merchant_note_rule", "official_rule_switch", "note_defaults"]
    schemaVersion: int = 1
    contentUpdatedAt: int | None = None
    sortKey: str | None = Field(default=None, max_length=80)
    naturalKey: str | None = Field(default=None, max_length=160)
    payload: dict[str, Any] | None = None


class PushRequest(BaseModel):
    protocolVersion: int = 1
    spaceId: str
    epoch: str
    items: list[PushItem] = Field(min_length=1, max_length=50)


class PullResponse(BaseModel):
    protocolVersion: int = 1
    spaceId: str
    epoch: str
    changes: list[dict[str, Any]]
    nextCursor: int
    hasMore: bool
    serverRevision: int


class AckRequest(BaseModel):
    throughRevision: int = Field(ge=0)
    epoch: str


class DeviceRevokeResponse(BaseModel):
    revoked: bool
