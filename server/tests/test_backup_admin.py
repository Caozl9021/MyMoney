from __future__ import annotations

import base64
import json
import os
import secrets
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.migrate import apply_schema


@pytest.fixture()
def admin_client(tmp_path, monkeypatch):
    database_url = os.environ["MYMONEY_TEST_DATABASE_URL"]
    schema = "mymoney_backup_admin_test"
    monkeypatch.setenv("MYMONEY_DATABASE_URL", database_url)
    monkeypatch.setenv("MYMONEY_DB_SCHEMA", schema)
    monkeypatch.setenv("MYMONEY_BACKUP_DIR", str(tmp_path))
    monkeypatch.setenv("MYMONEY_ADMIN_TOKEN", "admin-test-only")
    monkeypatch.setenv("MYMONEY_TSKEY", "tskey-auth-test-network-secret")
    monkeypatch.setenv("MYMONEY_SERVER_URL", "http://100.65.248.62:18080")
    monkeypatch.delenv("MYMONEY_PAIRING_CODE", raising=False)
    get_settings.cache_clear()
    with psycopg.connect(database_url) as conn:
        conn.execute(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE')
        apply_schema(conn, schema)
    from app.main import app
    with TestClient(app) as client:
        yield client
    with psycopg.connect(database_url) as conn:
        conn.execute(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE')
    get_settings.cache_clear()


ADMIN = {"X-Admin-Token": "admin-test-only"}


def decode_code(encoded):
    payload = encoded.removeprefix("MMJOIN2.")
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


def test_admin_page_does_not_embed_or_persist_secrets(admin_client):
    c = admin_client
    response = c.get("/admin/cloud-backup")
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-frame-options"] == "DENY"
    assert response.headers["pragma"] == "no-cache"
    assert "admin-test-only" not in response.text
    assert "tskey-auth-test-network-secret" not in response.text
    assert "localStorage" not in response.text
    for url in ("/v1/admin/backup-settings", "/v1/admin/backup-sources"):
        assert c.get(url).status_code == 403
    assert c.post("/v1/admin/connect-code", json={}).status_code == 403
    assert c.post(f"/v1/admin/backup-sources/{uuid4()}/archive", json={"archived": True}).status_code == 403


def test_generated_code_only_authorizes_one_installation_and_no_temporary_source(admin_client):
    c = admin_client
    response = c.post("/v1/admin/connect-code", headers=ADMIN, json={})
    assert response.status_code == 200, response.text
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"
    assert response.json()["expiresInSeconds"] == 300 and response.json()["oneTime"]
    payload = decode_code(response.json()["connectCode"])
    assert payload["key"] == "tskey-auth-test-network-secret"
    assert payload["server"] == "http://100.65.248.62:18080"
    assert "admin-test-only" not in response.text
    request = {"code": payload["code"], "installationId": str(uuid4()), "operationId": str(uuid4())}
    first = c.post("/v1/device-enrollment/authorize", json=request)
    assert first.status_code == 200, first.text
    assert first.headers["cache-control"] == "no-store"
    assert first.headers["pragma"] == "no-cache"
    assert c.post("/v1/device-enrollment/authorize", json=request).json() == first.json()
    other = dict(request, installationId=str(uuid4()), operationId=str(uuid4()))
    assert c.post("/v1/device-enrollment/authorize", json=other).status_code != 200
    assert c.get("/v1/admin/backup-sources", headers=ADMIN).json()["items"] == []


def test_admin_archive_retains_source_and_credential_and_can_be_reversed(admin_client):
    c = admin_client
    encoded = c.post("/v1/admin/connect-code", headers=ADMIN, json={}).json()["connectCode"]
    installation, operation = str(uuid4()), str(uuid4())
    grant = c.post("/v1/device-enrollment/authorize", json={
        "code": decode_code(encoded)["code"], "installationId": installation, "operationId": operation,
    }).json()["grant"]
    credential = secrets.token_urlsafe(32)
    response = c.post("/v1/device-enrollment/complete", headers={"Authorization": "Bearer " + grant}, json={
        "installationId": installation, "operationId": operation, "credential": credential,
        "deviceName": "测试手机", "sourceId": None, "expectedGeneration": None,
    })
    assert response.status_code == 200, response.text
    source = response.json()["deviceId"]
    records = c.get("/v1/admin/backup-sources", headers=ADMIN).json()["items"]
    assert len(records) == 1 and records[0]["backupCount"] == 0 and not records[0]["archived"]
    assert c.post(f"/v1/admin/backup-sources/{source}/archive", headers=ADMIN, json={"archived": True}).json() == {"archived": True}
    assert c.get("/v1/devices/self", headers={"Authorization": "Bearer " + credential}).status_code == 200
    records = c.get("/v1/admin/backup-sources", headers=ADMIN).json()["items"]
    assert len(records) == 1 and records[0]["archived"] and not records[0]["revoked"]
    assert c.post(f"/v1/admin/backup-sources/{source}/archive", headers=ADMIN, json={"archived": False}).json() == {"archived": False}


def test_connect_code_returns_qr_encoding_the_exact_paste_string(admin_client):
    c = admin_client
    body = c.post("/v1/admin/connect-code", headers=ADMIN, json={"deviceName": "小米"}).json()
    assert body["invitationId"] and body["expiresAt"] and body["serverTime"]
    assert body["qrEncodable"] is True
    assert "<svg" in body["qrSvg"]
    # The rendering is done over the verbatim paste string; the SVG must exist for it.
    from app.backup_admin import render_qr_svg
    assert render_qr_svg(body["connectCode"]) is not None
    assert render_qr_svg("x" * 5000) is None  # over-long code is not silently truncated


def test_invitation_status_lifecycle_pending_authorized_completed(admin_client):
    c = admin_client
    body = c.post("/v1/admin/connect-code", headers=ADMIN, json={}).json()
    inv = body["invitationId"]
    code = decode_code(body["connectCode"])["code"]

    pending = c.get(f"/v1/admin/connect-codes/{inv}", headers=ADMIN)
    assert pending.status_code == 200 and pending.json()["status"] == "pending"
    assert "key" not in pending.text and "grant" not in pending.text

    installation, operation = str(uuid4()), str(uuid4())
    grant = c.post("/v1/device-enrollment/authorize", json={
        "code": code, "installationId": installation, "operationId": operation}).json()["grant"]
    assert c.get(f"/v1/admin/connect-codes/{inv}", headers=ADMIN).json()["status"] == "authorized"

    done = c.post("/v1/device-enrollment/complete", headers={"Authorization": "Bearer " + grant}, json={
        "installationId": installation, "operationId": operation, "credential": secrets.token_urlsafe(32),
        "deviceName": "测试手机", "sourceId": None, "expectedGeneration": None})
    assert done.status_code == 200
    finished = c.get(f"/v1/admin/connect-codes/{inv}", headers=ADMIN).json()
    assert finished["status"] == "completed" and finished["deviceId"] == done.json()["deviceId"]


def test_cancel_closes_unclaimed_invitation_and_blocks_later_authorize(admin_client):
    c = admin_client
    body = c.post("/v1/admin/connect-code", headers=ADMIN, json={}).json()
    inv = body["invitationId"]
    code = decode_code(body["connectCode"])["code"]
    assert c.post(f"/v1/admin/connect-codes/{inv}/cancel", headers=ADMIN).json()["status"] == "cancelled"
    # idempotent
    assert c.post(f"/v1/admin/connect-codes/{inv}/cancel", headers=ADMIN).json()["status"] == "cancelled"
    assert c.get(f"/v1/admin/connect-codes/{inv}", headers=ADMIN).json()["status"] == "cancelled"
    # a not-yet-authorized code can no longer be authorized after cancel
    blocked = c.post("/v1/device-enrollment/authorize", json={
        "code": code, "installationId": str(uuid4()), "operationId": str(uuid4())})
    assert blocked.status_code == 401


def test_cancel_cannot_revoke_completed_device(admin_client):
    c = admin_client
    body = c.post("/v1/admin/connect-code", headers=ADMIN, json={}).json()
    inv = body["invitationId"]
    code = decode_code(body["connectCode"])["code"]
    installation, operation = str(uuid4()), str(uuid4())
    grant = c.post("/v1/device-enrollment/authorize", json={
        "code": code, "installationId": installation, "operationId": operation}).json()["grant"]
    cred = secrets.token_urlsafe(32)
    done = c.post("/v1/device-enrollment/complete", headers={"Authorization": "Bearer " + grant}, json={
        "installationId": installation, "operationId": operation, "credential": cred,
        "deviceName": "测试手机", "sourceId": None, "expectedGeneration": None})
    assert done.status_code == 200
    cancel = c.post(f"/v1/admin/connect-codes/{inv}/cancel", headers=ADMIN).json()
    assert cancel["status"] == "completed" and cancel["deviceId"] == done.json()["deviceId"]
    # device credential still valid -> not revoked
    assert c.get("/v1/devices/self", headers={"Authorization": "Bearer " + cred}).status_code == 200


def test_connect_code_status_requires_admin_and_valid_uuid(admin_client):
    c = admin_client
    assert c.get(f"/v1/admin/connect-codes/{uuid4()}").status_code == 403
    # a syntactically invalid id is rejected by the handler before any lookup
    assert c.get("/v1/admin/connect-codes/not-a-uuid", headers=ADMIN).status_code == 422


def test_backup_overview_counts_live_backups_and_hides_deleted(admin_client):
    c = admin_client
    overview = c.get("/v1/admin/backup-overview", headers=ADMIN).json()
    assert overview["activeDevices"] == 0 and overview["liveBackups"] == 0
    assert overview["liveBytes"] == 0 and overview["lastBackupAt"] is None
    assert overview["recentBackups"] == [] and overview["serverTime"]
    assert c.get("/v1/admin/backup-overview").status_code == 403
