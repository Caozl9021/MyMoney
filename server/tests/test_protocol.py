from __future__ import annotations

import os

import psycopg
import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.migrate import apply_schema


@pytest.fixture()
def client():
    database_url = os.environ["MYMONEY_TEST_DATABASE_URL"]
    schema = "mymoney_home_test"
    os.environ["MYMONEY_DATABASE_URL"] = database_url
    os.environ["MYMONEY_DB_SCHEMA"] = schema
    get_settings.cache_clear()
    with psycopg.connect(database_url) as conn:
        conn.execute(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE')
        apply_schema(conn, schema)
    from app.main import app
    from app.pairing import PAIRING

    code = PAIRING.issue()
    with TestClient(app) as test_client:
        yield test_client, code
    with psycopg.connect(database_url) as conn:
        conn.execute(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE')


def pair(client, code: str, name: str = "测试手机") -> dict:
    response = client.post("/v1/pair", json={"code": code, "deviceName": name})
    assert response.status_code == 200, response.text
    return response.json()


def headers(pairing: dict) -> dict:
    return {"Authorization": f"Bearer {pairing['token']}"}


def base_item(operation_id: str, payload: dict | None = None) -> dict:
    return {
        "operationId": operation_id,
        "epoch": "",
        "itemId": "personal_test123",
        "baseRevision": 0,
        "operation": "put",
        "kind": "recognition_rule",
        "schemaVersion": 1,
        "contentUpdatedAt": 100,
        "sortKey": "1000",
        "naturalKey": "personal_test123",
        "payload": payload or {
            "id": "personal_test123",
            "name": "测试",
            "app": "com.tencent.mm",
            "inputType": "PAGE",
            "billType": "Expend",
            "keyword": "测试",
            "amountSource": "AUTO",
            "merchant": "",
            "method": "",
            "enabled": True,
            "updatedAt": 100,
        },
    }


def test_pair_push_conflict_pull_delete(client):
    test_client, code = client
    paired = pair(test_client, code)
    assert paired["protocolVersion"] == 1
    h = headers(paired)
    epoch = paired["epoch"]

    response = test_client.get("/v1/sync/snapshot", headers=h)
    assert response.status_code == 200
    assert response.json()["items"] == []

    item = base_item("op-0000000000000001")
    item["epoch"] = epoch
    response = test_client.post("/v1/sync/push", headers=h, json={"protocolVersion": 1, "spaceId": paired["spaceId"], "epoch": epoch, "items": [item]})
    assert response.status_code == 200, response.text
    result = response.json()["items"][0]
    assert result["status"] == "ok" and result["revision"] == 1

    # Same operation replay returns the recorded result.
    response = test_client.post("/v1/sync/push", headers=h, json={"protocolVersion": 1, "spaceId": paired["spaceId"], "epoch": epoch, "items": [item]})
    assert response.status_code == 200
    assert response.json()["items"][0]["revision"] == 1

    # Stale base revision is a conflict, not an overwrite.
    stale = base_item("op-0000000000000002")
    stale["epoch"] = epoch
    response = test_client.post("/v1/sync/push", headers=h, json={"protocolVersion": 1, "spaceId": paired["spaceId"], "epoch": epoch, "items": [stale]})
    assert response.status_code == 200
    assert response.json()["items"][0]["status"] == "conflict"
    assert response.json()["items"][0]["remoteRevision"] == 1

    response = test_client.get("/v1/sync/pull?after=0", headers=h)
    assert response.status_code == 200
    assert response.json()["changes"][0]["revision"] == 1
    assert response.json()["nextCursor"] == 1

    ack = test_client.post("/v1/sync/ack", headers=h, json={"throughRevision": 1, "epoch": epoch})
    assert ack.status_code == 200

    delete = base_item("op-0000000000000003")
    delete["epoch"] = epoch
    delete["operation"] = "delete"
    delete["baseRevision"] = 1
    delete["payload"] = None
    response = test_client.post("/v1/sync/push", headers=h, json={"protocolVersion": 1, "spaceId": paired["spaceId"], "epoch": epoch, "items": [delete]})
    assert response.status_code == 200, response.text
    assert response.json()["items"][0]["snapshot"]["deletedAt"] is not None

    # A put against a tombstone must conflict; an explicit restore can revive it.
    revive = base_item("op-0000000000000004")
    revive["epoch"] = epoch
    revive["baseRevision"] = 2
    response = test_client.post("/v1/sync/push", headers=h, json={"protocolVersion": 1, "spaceId": paired["spaceId"], "epoch": epoch, "items": [revive]})
    assert response.status_code == 200
    assert response.json()["items"][0]["status"] == "conflict"


def test_pairing_code_is_one_time(client):
    test_client, code = client
    pair(test_client, code)
    response = test_client.post("/v1/pair", json={"code": code, "deviceName": "第二台"})
    assert response.status_code == 401


def test_permanent_pairing_code_is_reusable():
    from app.pairing import PairingStore

    store = PairingStore()
    code = store.issue(ttl_seconds=0)
    assert store.verify(code) is True
    assert store.verify(code.lower()) is True


def test_push_multiple_items_in_one_batch(client):
    test_client, code = client
    paired = pair(test_client, code)
    h = headers(paired)
    epoch = paired["epoch"]
    first = base_item("op-0000000000000010")
    first["epoch"] = epoch
    second = base_item("op-0000000000000011")
    second["epoch"] = epoch
    second["itemId"] = "personal_test124"
    second["naturalKey"] = "personal_test124"
    second["payload"] = dict(second["payload"], id="personal_test124", keyword="测试二")
    response = test_client.post(
        "/v1/sync/push",
        headers=h,
        json={"protocolVersion": 1, "spaceId": paired["spaceId"], "epoch": epoch, "items": [first, second]},
    )
    assert response.status_code == 200, response.text
    results = response.json()["items"]
    assert [r["status"] for r in results] == ["ok", "ok"]
    assert [r["revision"] for r in results] == [1, 2]
    snapshot = test_client.get("/v1/sync/snapshot", headers=h)
    assert snapshot.status_code == 200
    assert len(snapshot.json()["items"]) == 2
