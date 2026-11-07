from __future__ import annotations

import hashlib
import json
import os
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.migrate import apply_schema


@pytest.fixture()
def client(tmp_path, monkeypatch):
    database_url = os.environ["MYMONEY_TEST_DATABASE_URL"]
    schema = "mymoney_backup_delete_test"
    monkeypatch.setenv("MYMONEY_DATABASE_URL", database_url)
    monkeypatch.setenv("MYMONEY_DB_SCHEMA", schema)
    monkeypatch.setenv("MYMONEY_BACKUP_DIR", str(tmp_path))
    monkeypatch.delenv("MYMONEY_PAIRING_CODE", raising=False)
    get_settings.cache_clear()
    with psycopg.connect(database_url) as conn:
        conn.execute(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE')
        apply_schema(conn, schema)
    from app.main import app
    from app.pairing import PAIRING
    code = PAIRING.issue()
    with TestClient(app) as client:
        paired = client.post("/v1/pair", json={"code": code, "deviceName": "删除测试设备"}).json()
        yield client, paired, tmp_path
    with psycopg.connect(database_url) as conn:
        conn.execute(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE')
    get_settings.cache_clear()


def raw_snapshot(bills=None):
    return json.dumps({"kind": "MyMoneyBackup", "format": 8, "exportedAt": 1793520000000,
                       "bills": bills or [], "history": []}, ensure_ascii=False).encode()


def headers(pair, raw):
    return {"Authorization": f"Bearer {pair['token']}", "Content-Type": "application/json",
            "X-Content-SHA256": hashlib.sha256(raw).hexdigest(),
            "X-Space-Epoch": pair["epoch"], "X-App-Version": "1.9.0/155"}


def upload(c, pair, raw=None, backup_id=None):
    raw = raw_snapshot([{"p": 123, "ty": "REFUND", "t": 1, "fp": "f", "sp": "", "st": "t"}]) if raw is None else raw
    backup_id = backup_id or str(uuid4())
    response = c.put(f"/v1/backups/{backup_id}", content=raw, headers=headers(pair, raw))
    assert response.status_code == 200, response.text
    return backup_id, raw


def delete(c, pair, ids, operation_id=None):
    return c.post("/v1/backups/delete",
                  json={"operationId": operation_id or str(uuid4()), "backupIds": ids},
                  headers={"Authorization": f"Bearer {pair['token']}"})


def test_delete_tombstones_hides_and_recycles(client):
    c, pair, directory = client
    first, _ = upload(c, pair)
    second, _ = upload(c, pair)
    response = delete(c, pair, [first])
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["deletedCount"] == 1 and body["recyclePending"] is False
    assert body["deletedBytes"] == body["recycledBytes"] > 0 and body["pendingBytes"] == 0
    assert not list(directory.rglob(f"{first}.json"))
    listed = c.get("/v1/backups", headers={"Authorization": f"Bearer {pair['token']}"}).json()
    assert [item["id"] for item in listed["items"]] == [second]
    auth = {"Authorization": f"Bearer {pair['token']}"}
    assert c.get(f"/v1/backups/{first}", headers=auth).status_code == 404
    assert c.get(f"/v1/backups/{first}/status", headers=auth).status_code == 404
    assert c.get(f"/v1/backups/{second}/status", headers=auth).status_code == 200
    with psycopg.connect(os.environ["MYMONEY_TEST_DATABASE_URL"]) as conn:
        conn.execute("SET search_path TO mymoney_backup_delete_test")
        row = conn.execute("SELECT deleted_at,recycled_at FROM ledger_backups WHERE id=%s", (first,)).fetchone()
        assert row and row[0] is not None and row[1] is not None


def test_delete_operation_is_idempotent(client):
    c, pair, _ = client
    first, _ = upload(c, pair)
    operation = str(uuid4())
    once = delete(c, pair, [first], operation)
    twice = delete(c, pair, [first], operation)
    assert once.status_code == 200 and twice.status_code == 200
    assert once.json()["deletedCount"] == twice.json()["deletedCount"] == 1
    assert once.json()["deletedBytes"] == twice.json()["deletedBytes"]


def test_same_operation_with_different_ids_conflicts(client):
    c, pair, _ = client
    first, _ = upload(c, pair)
    second, _ = upload(c, pair)
    operation = str(uuid4())
    assert delete(c, pair, [first], operation).status_code == 200
    assert delete(c, pair, [second], operation).status_code == 409


def test_batch_is_atomic_on_unknown_id(client):
    c, pair, _ = client
    first, _ = upload(c, pair)
    second, _ = upload(c, pair)
    response = delete(c, pair, [first, str(uuid4())])
    assert response.status_code == 404
    auth = {"Authorization": f"Bearer {pair['token']}"}
    listed = c.get("/v1/backups", headers=auth).json()
    assert len(listed["items"]) == 2  # nothing tombstoned by the failed batch
    assert c.get(f"/v1/backups/{first}/status", headers=auth).status_code == 200


def test_already_deleted_is_reported_not_silently_ignored(client):
    c, pair, _ = client
    first, _ = upload(c, pair)
    assert delete(c, pair, [first]).status_code == 200
    assert delete(c, pair, [first]).status_code == 409


def test_deleted_id_cannot_be_reuploaded(client):
    c, pair, _ = client
    first, raw = upload(c, pair)
    assert delete(c, pair, [first]).status_code == 200
    assert c.put(f"/v1/backups/{first}", content=raw, headers=headers(pair, raw)).status_code == 410
    upload(c, pair)  # a fresh id still works


def test_delete_is_scoped_to_owning_device(client):
    c, pair, _ = client
    from app.pairing import PAIRING
    other = c.post("/v1/pair", json={"code": PAIRING.issue(), "deviceName": "另一设备"}).json()
    assert other["spaceId"] == pair["spaceId"]
    first, _ = upload(c, pair)
    response = delete(c, other, [first])
    assert response.status_code == 404
    assert c.get(f"/v1/backups/{first}/status",
                 headers={"Authorization": f"Bearer {pair['token']}"}).status_code == 200


def test_file_failure_leaves_pending_recycle_then_sweep_reclaims(client, monkeypatch):
    c, pair, directory = client
    first, _ = upload(c, pair)
    from app import backups

    import pathlib

    original_unlink = pathlib.Path.unlink

    def flaky(self, *args, **kwargs):
        if self.name == f"{first}.json":
            raise OSError("disk busy")
        return original_unlink(self, *args, **kwargs)

    monkeypatch.setattr(pathlib.Path, "unlink", flaky)
    body = c.post("/v1/backups/delete",
                  json={"operationId": str(uuid4()), "backupIds": [first]},
                  headers={"Authorization": f"Bearer {pair['token']}"}).json()
    assert body["recyclePending"] is True and body["pendingBytes"] > 0 and body["recycledBytes"] == 0
    assert list(directory.rglob(f"{first}.json"))
    monkeypatch.setattr(pathlib.Path, "unlink", original_unlink)
    swept = backups.recycle_pending_backups()
    assert swept["swept"] == 1 and swept["pendingCount"] == 0
    assert not list(directory.rglob(f"{first}.json"))


def test_revoked_device_cannot_delete(client):
    c, pair, _ = client
    first, _ = upload(c, pair)
    token = pair["token"]
    with psycopg.connect(os.environ["MYMONEY_TEST_DATABASE_URL"]) as conn:
        conn.execute("SET search_path TO mymoney_backup_delete_test")
        conn.execute("UPDATE devices SET revoked_at=now()")
        conn.commit()
    response = c.post("/v1/backups/delete",
                      json={"operationId": str(uuid4()), "backupIds": [first]},
                      headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 401


def test_backup_context_merges_identity_capabilities_and_latest(client):
    c, pair, _ = client
    auth = {"Authorization": f"Bearer {pair['token']}"}
    empty = c.get("/v1/backup-context", headers=auth).json()
    assert empty["identity"]["deviceId"] == pair["deviceId"]
    assert empty["capabilities"]["deleteBackups"] is True and empty["latestBackup"] is None
    first, _ = upload(c, pair)
    body = c.get("/v1/backup-context", headers=auth).json()
    assert body["latestBackup"]["id"] == first
    assert delete(c, pair, [first]).status_code == 200
    assert c.get("/v1/backup-context", headers=auth).json()["latestBackup"] is None
    assert c.get("/v1/backup-capabilities").json()["backupContext"] is True


def test_backup_context_reports_installation_binding(client):
    c, pair, _ = client
    installation = str(uuid4())
    auth = {"Authorization": f"Bearer {pair['token']}"}
    unbound = c.get("/v1/backup-context", headers=auth).json()
    assert unbound["installationId"] is None and unbound["installationMatches"] is None
    assert c.post("/v1/devices/self/bind-installation", json={"installationId": installation},
                  headers=auth).status_code == 200
    bound = c.get(f"/v1/backup-context?installationId={installation}", headers=auth).json()
    assert bound["installationId"] == installation and bound["installationMatches"] is True
    assert c.get(f"/v1/backup-context?installationId={uuid4()}", headers=auth).json()["installationMatches"] is False
