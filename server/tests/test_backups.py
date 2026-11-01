from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.migrate import apply_schema


@pytest.fixture()
def client(tmp_path, monkeypatch):
    database_url = os.environ["MYMONEY_TEST_DATABASE_URL"]
    schema = "mymoney_home_backup_test"
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
        paired = client.post("/v1/pair", json={"code": code, "deviceName": "快照测试设备"}).json()
        yield client, paired, tmp_path
    with psycopg.connect(database_url) as conn:
        conn.execute(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE')
    get_settings.cache_clear()


def raw_snapshot(bills=None):
    return json.dumps({"kind": "MyMoneyBackup", "format": 8, "exportedAt": 1793520000000,
                       "bills": bills or [], "history": []}, ensure_ascii=False).encode()


def headers(pair, raw):
    return {"Authorization": f"Bearer {pair['token']}", "Content-Type": "application/json",
            "X-Content-SHA256": hashlib.sha256(raw).hexdigest(), "X-Space-Epoch": pair["epoch"], "X-App-Version": "1.7.0/121"}


def test_upload_list_download_idempotence(client):
    c, pair, directory = client
    raw = raw_snapshot([{"p": 123, "ty": "REFUND", "t": 1, "fp": "test", "sp": "", "st": "test"}])
    h = headers(pair, raw)
    backup_id = str(uuid4())
    response = c.put(f"/v1/backups/{backup_id}", content=raw, headers=h)
    assert response.status_code == 200, response.text
    row = response.json()
    assert row["billCount"] == 1 and row["sizeBytes"] == len(raw)
    assert row["deviceName"] == "快照测试设备"
    assert c.put(f"/v1/backups/{backup_id}", content=raw, headers=h).json() == row
    listed = c.get("/v1/backups", headers=h).json()
    assert listed["items"] == [row] and not listed["hasMore"]
    response = c.get(f"/v1/backups/{backup_id}", headers=h)
    assert response.content == raw and response.headers["x-content-sha256"] == h["X-Content-SHA256"]
    changed = raw_snapshot()
    assert c.put(f"/v1/backups/{backup_id}", content=changed, headers=headers(pair, changed)).status_code == 409
    assert not list(directory.rglob("*.part"))


def test_invalid_uploads_never_create_ready_index(client):
    c, pair, directory = client
    raw = raw_snapshot()
    h = headers(pair, raw)
    backup_id = uuid4()
    assert c.put(f"/v1/backups/{backup_id}", content=raw).status_code == 401
    bad = dict(h, **{"X-Content-SHA256": "0" * 64})
    assert c.put(f"/v1/backups/{backup_id}", content=raw, headers=bad).status_code == 422
    bad = dict(h, **{"Content-Length": str(len(raw) + 1)})
    assert c.put(f"/v1/backups/{backup_id}", content=raw, headers=bad).status_code == 422
    bad = dict(h, **{"Content-Length": str(201 * 1024 * 1024)})
    assert c.put(f"/v1/backups/{backup_id}", content=raw, headers=bad).status_code == 413
    bad = dict(h, **{"X-Space-Epoch": str(uuid4())})
    assert c.put(f"/v1/backups/{backup_id}", content=raw, headers=bad).status_code == 409
    for data in (b'{}', raw.replace(b'"format": 8', b'"format": 9'), raw_snapshot([{"p": -1}]), b'{'):
        assert c.put(f"/v1/backups/{backup_id}", content=data, headers=headers(pair, data)).status_code == 422
    assert c.get("/v1/backups", headers=h).json()["items"] == []
    assert not list(directory.rglob("*.part")) and not list(directory.rglob("*.json"))


def test_pagination_corruption_and_space_isolation(client):
    from app.db import connection
    from app.auth import hash_token
    c, pair, directory = client
    raw = raw_snapshot()
    h = headers(pair, raw)
    ids = [str(uuid4()) for _ in range(3)]
    for backup_id in ids:
        assert c.put(f"/v1/backups/{backup_id}", content=raw, headers=h).status_code == 200
    first = c.get("/v1/backups?limit=2", headers=h).json()
    second = c.get(f"/v1/backups?limit=2&offset={first['nextOffset']}", headers=h).json()
    assert first["hasMore"] and len(first["items"]) == 2 and not second["hasMore"]
    assert len({r["id"] for r in first["items"] + second["items"]}) == 3
    other_space, other_device = uuid4(), uuid4()
    with connection() as conn:
        conn.execute("INSERT INTO sync_spaces(id,epoch,name) VALUES (%s,%s,'other')", (other_space, uuid4()))
        conn.execute("INSERT INTO devices(space_id,id,name,token_hash) VALUES (%s,%s,'other',%s)",
                     (other_space, other_device, hash_token("other-token")))
        conn.commit()
    other = {"Authorization": "Bearer other-token"}
    assert c.get("/v1/backups", headers=other).json()["items"] == []
    assert c.get(f"/v1/backups/{ids[0]}", headers=other).status_code == 404
    path = directory / pair["spaceId"] / f"{ids[0]}.json"
    path.write_bytes(b'broken')
    assert c.get(f"/v1/backups/{ids[0]}", headers=h).status_code == 409
    # A matching verified upload repairs a lost file, never changes the index.
    assert c.put(f"/v1/backups/{ids[0]}", content=raw, headers=h).status_code == 200
    assert c.get(f"/v1/backups/{ids[0]}", headers=h).content == raw
    c.post("/v1/device/revoke", headers=h)
    assert c.get("/v1/backups", headers=h).status_code == 401


def test_disk_failure_preserves_previous_and_cleans_part(client, monkeypatch):
    import app.backups as backups
    c, pair, directory = client
    raw = raw_snapshot()
    h = headers(pair, raw)
    first = uuid4()
    assert c.put(f"/v1/backups/{first}", content=raw, headers=h).status_code == 200
    def fail(*args):
        raise OSError("injected disk full")
    monkeypatch.setattr(backups.os, "replace", fail)
    assert c.put(f"/v1/backups/{uuid4()}", content=raw, headers=h).status_code == 507
    assert len(c.get("/v1/backups", headers=h).json()["items"]) == 1
    assert c.get(f"/v1/backups/{first}", headers=h).content == raw
    assert not list(directory.rglob("*.part"))


def test_orphan_file_recovered_by_retry(client, monkeypatch):
    import app.backups as backups
    from app.db import connection
    from contextlib import contextmanager
    c, pair, directory = client
    raw = raw_snapshot()
    h = headers(pair, raw)
    backup_id = uuid4()
    real_connection = backups.connection
    @contextmanager
    def failed_connection():
        with real_connection() as conn:
            class Proxy:
                def execute(self, query, args=None):
                    if query.startswith("INSERT INTO ledger_backups"):
                        raise RuntimeError("injected database failure after rename")
                    return conn.execute(query, args)
                def commit(self):
                    conn.commit()
            yield Proxy()
    monkeypatch.setattr(backups, "connection", failed_connection)
    with pytest.raises(RuntimeError, match="injected database failure"):
        c.put(f"/v1/backups/{backup_id}", content=raw, headers=h)
    assert list(directory.rglob("*.json")) and not list(directory.rglob("*.part"))
    monkeypatch.setattr(backups, "connection", real_connection)
    assert c.get("/v1/backups", headers=h).json()["items"] == []
    assert c.put(f"/v1/backups/{backup_id}", content=raw, headers=h).status_code == 200
    assert len(c.get("/v1/backups", headers=h).json()["items"]) == 1


def test_restart_and_read_only_audit(client):
    from app.backups import audit_files
    from app.main import app
    c, pair, directory = client
    raw = raw_snapshot()
    h = headers(pair, raw)
    backup_id = uuid4()
    assert c.put(f"/v1/backups/{backup_id}", content=raw, headers=h).status_code == 200
    space = directory / pair["spaceId"]
    orphan = space / f"{uuid4()}.json"
    orphan.write_bytes(raw)
    partial = space / ".interrupted.part"
    partial.write_bytes(b'half')
    with TestClient(app) as restarted:
        assert len(restarted.get("/v1/backups", headers=h).json()["items"]) == 1
        assert restarted.get(f"/v1/backups/{backup_id}", headers=h).content == raw
    audit = audit_files()
    assert audit["deletedCount"] == 0
    assert len(audit["orphanFiles"]) == 1 and len(audit["partialFiles"]) == 1
    assert audit["missingFiles"] == [] and audit["corruptFiles"] == []
    assert orphan.is_file() and partial.is_file()
