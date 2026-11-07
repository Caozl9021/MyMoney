"""Portable backup validation, no database or production service connection."""
from __future__ import annotations

import copy
import json
from uuid import uuid4

import pytest
from fastapi import HTTPException

from app.backups import validate_snapshot


def bill(identity=1, **changes):
    row = dict(id=identity, priceCents=1234, type="EXPENSE", timeMillis=1700000000000,
               sourcePkg="simulation", strategy="test", fingerprint="fixture", createdAt=1700000000000,
               assetKey="fixture-key", assetLinked=False, transferFeeCents=0,
               account="原账户", note="人工备注", recognitionId=None, feeParentKey=None)
    row.update(changes)
    return row


def deletion(rows=None):
    rows = rows or [bill()]
    principal = rows[0]
    return dict(eventId=str(uuid4()), deletedAt=1700000000001, origin="detail",
                principalId=principal["id"], recognitionId=principal.get("recognitionId"),
                groupId=principal["assetKey"] or f"bill:{principal['id']}", snapshotVersion=1,
                snapshotJson=json.dumps({"bills": rows}, ensure_ascii=False))


def snapshot(**changes):
    value = dict(kind="MyMoneyBackup", format=9, exportedAt=1700000000002, bills=[], deletions=[])
    value.update(changes)
    return value


def validate(tmp_path, value):
    path = tmp_path / "snapshot.json"
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    return validate_snapshot(path)


@pytest.mark.parametrize("version", [8, 9, 10])
def test_supported_versions_keep_actual_schema_and_exclude_log_snapshots_from_bill_count(tmp_path, version):
    value = snapshot(format=version)
    if version == 8:
        value.pop("deletions")
    else:
        value["deletions"] = [deletion()]
    count, _, schema = validate(tmp_path, value)
    assert count == 0 and schema == version


def test_transfer_group_and_fallback_group_id_are_valid(tmp_path):
    rows = [bill(type="TRANSFER", transferFeeCents=50, accountId="from"),
            bill(2, priceCents=50, accountId="from", feeParentKey="fixture-key", assetKey="fee-key")]
    assert validate(tmp_path, snapshot(deletions=[deletion(rows)]))[0] == 0
    assert validate(tmp_path, snapshot(deletions=[deletion([bill(assetKey="")])]))[0] == 0


@pytest.mark.parametrize("field,value", [
    ("eventId", "not-a-uuid"), ("deletedAt", True), ("deletedAt", 0),
    ("principalId", 2), ("snapshotVersion", 2), ("origin", "bad origin"),
    ("groupId", "wrong"), ("recognitionId", "wrong"), ("snapshotJson", "{}"),
    ("snapshotJson", "{invalid"), ("snapshotJson", "x" * (1024 * 1024 + 1)),
])
def test_invalid_deletion_never_passes_validation(tmp_path, field, value):
    event = deletion()
    event[field] = value
    with pytest.raises(HTTPException) as error:
        validate(tmp_path, snapshot(deletions=[event]))
    assert error.value.status_code == 422


@pytest.mark.parametrize("change", [
    {"priceCents": -1}, {"priceCents": 1.5}, {"assetLinked": "false"},
    {"type": "OTHER"}, {"accountId": ""}, {"note": 123},
    {"transferFeeCents": 50}, {"createdAt": True},
])
def test_invalid_frozen_bill_is_rejected(tmp_path, change):
    with pytest.raises(HTTPException):
        validate(tmp_path, snapshot(deletions=[deletion([bill(**change)])]))


def test_incomplete_or_conflicting_audit_groups_are_rejected(tmp_path):
    event = deletion()
    cases = [snapshot(deletions=[event, copy.deepcopy(event)]),
             snapshot(format=8, deletions=[event]), snapshot(deletions=None),
             snapshot(format=11),
             snapshot(deletions=[deletion([bill(type="TRANSFER", transferFeeCents=50)])]),
             snapshot(deletions=[deletion([bill(), bill(2, feeParentKey="fixture-key")])]),
             snapshot(deletions=[deletion([bill(type="TRANSFER", transferFeeCents=50, accountId="from"),
                                           bill(2, priceCents=49, accountId="from", feeParentKey="fixture-key")])])]
    for value in cases:
        with pytest.raises(HTTPException):
            validate(tmp_path, value)


def test_whitespace_asset_key_cannot_link_a_fee_even_with_valid_fallback_group(tmp_path):
    rows = [bill(type="TRANSFER", assetKey=" ", transferFeeCents=50, accountId="from"),
            bill(2, priceCents=50, accountId="from", feeParentKey=" ", assetKey="fee")]
    event = deletion(rows)
    event["groupId"] = "bill:1"
    with pytest.raises(HTTPException):
        validate(tmp_path, snapshot(deletions=[event]))


def test_json10_identity_and_duplicate_validation(tmp_path):
    first = dict(p=100, ty="EXPENSE", t=1, fp="a", sp="test", st="test", sid="source-id")
    assert validate(tmp_path, snapshot(format=10,bills=[first]))[2] == 10
    for value in ([first,first], [dict(first,sid=123)], [dict(first,sid="x" * 129)]):
        with pytest.raises(HTTPException):
            validate(tmp_path,snapshot(format=10,bills=value))
