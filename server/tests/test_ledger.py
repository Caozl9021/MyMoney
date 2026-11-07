from __future__ import annotations

import os
import uuid

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


def pair(test_client, code: str, name: str = "测试手机") -> dict:
    r = test_client.post("/v1/pair", json={"code": code, "deviceName": name})
    assert r.status_code == 200, r.text
    return r.json()


def hdr(p: dict) -> dict:
    return {"Authorization": f"Bearer {p['token']}"}


LEDGER = str(uuid.uuid4())


def bill_item(op: str, sync_id: str, base_rev: int = 0, epoch: str = "", amount: int = 100,
              operation: str = "put", item_id: str | None = None) -> dict:
    item_id = item_id or ("bill:" + sync_id)
    return {
        "operationId": op,
        "epoch": epoch,
        "ledgerId": LEDGER,
        "itemId": item_id,
        "itemKind": "bill",
        "baseRevision": base_rev,
        "operation": operation,
        "schemaVersion": 1,
        "contentUpdatedAt": 100,
        "payload": {"syncId": sync_id, "priceCents": amount, "type": "EXPENSE"},
    }


def push(test_client, p, items):
    return test_client.post(
        "/v1/ledger/push",
        headers=hdr(p),
        json={"protocolVersion": 1, "spaceId": p["spaceId"], "ledgerId": LEDGER, "epoch": p["epoch"], "items": items},
    )


def test_push_snapshot_pull_idempotent(client):
    tc, code = client
    p = pair(tc, code)
    sid = str(uuid.uuid4())
    body = push(tc, p, [bill_item("op_" + "0" * 16, sid, epoch=p["epoch"])])
    assert body.status_code == 200, body.text
    res = body.json()["items"][0]
    assert res["status"] == "ok"
    rev = res["revision"]
    assert rev == 1

    # 幂等重放：同 operationId 同内容 → 返回缓存，不再递增
    body2 = push(tc, p, [bill_item("op_" + "0" * 16, sid, epoch=p["epoch"])])
    assert body2.json()["items"][0] == res
    # 同 operationId 不同内容 → 409
    bad = push(tc, p, [bill_item("op_" + "0" * 16, sid, epoch=p["epoch"], amount=999)])
    assert bad.status_code == 409

    snap = tc.get("/v1/ledger/snapshot", headers=hdr(p), params={"ledgerId": LEDGER}).json()
    assert snap["serverRevision"] == 1
    assert len(snap["items"]) == 1
    assert snap["items"][0]["payload"]["priceCents"] == 100

    pull = tc.get("/v1/ledger/pull", headers=hdr(p),
                  params={"ledgerId": LEDGER, "after": 0}).json()
    assert len(pull["changes"]) == 1
    assert pull["nextCursor"] == 1

    ack = tc.post("/v1/ledger/ack", headers=hdr(p),
                  params={"ledgerId": LEDGER}, json={"throughRevision": 1, "epoch": p["epoch"]})
    assert ack.status_code == 200


def test_optimistic_conflict(client):
    tc, code = client
    p = pair(tc, code)
    sid = str(uuid.uuid4())
    push(tc, p, [bill_item("a" + "0" * 16, sid, base_rev=0, epoch=p["epoch"])])
    # 第二次用旧 baseRevision=0 → 冲突
    r = push(tc, p, [bill_item("b" + "0" * 16, sid, base_rev=0, epoch=p["epoch"], amount=200)])
    res = r.json()["items"][0]
    assert res["status"] == "conflict"
    assert res["remoteRevision"] == 1
    assert res["remote"]["payload"]["priceCents"] == 100
    # 用正确 baseRevision=1 → 成功 rev=2
    r2 = push(tc, p, [bill_item("c" + "0" * 16, sid, base_rev=1, epoch=p["epoch"], amount=200)])
    assert r2.json()["items"][0]["revision"] == 2


def test_tombstone_delete(client):
    tc, code = client
    p = pair(tc, code)
    sid = str(uuid.uuid4())
    push(tc, p, [bill_item("d" + "0" * 16, sid, base_rev=0, epoch=p["epoch"])])
    # 删除 → 墓碑
    r = push(tc, p, [bill_item("e" + "0" * 16, sid, base_rev=1, epoch=p["epoch"], operation="delete")])
    assert r.json()["items"][0]["status"] == "ok"
    assert r.json()["items"][0]["snapshot"]["deletedAt"] is not None
    # 重复删除 → 冲突
    r2 = push(tc, p, [bill_item("f" + "0" * 16, sid, base_rev=2, epoch=p["epoch"], operation="delete")])
    assert r2.json()["items"][0]["status"] == "conflict"
    # 删除后 pull 能看到 delete 操作
    pull = tc.get("/v1/ledger/pull", headers=hdr(p), params={"ledgerId": LEDGER, "after": 1}).json()
    assert pull["changes"][0]["operation"] == "delete"


def test_wrong_epoch_rejected(client):
    tc, code = client
    p = pair(tc, code)
    sid = str(uuid.uuid4())
    r = push(tc, p, [bill_item("g" + "0" * 16, sid, epoch="00000000-0000-0000-0000-000000000000")])
    assert r.status_code == 409


def test_ledger_isolation(client):
    tc, code = client
    p = pair(tc, code)
    sid = str(uuid.uuid4())
    push(tc, p, [bill_item("h" + "0" * 16, sid, epoch=p["epoch"])])
    other_ledger = str(uuid.uuid4())
    snap = tc.get("/v1/ledger/snapshot", headers=hdr(p), params={"ledgerId": other_ledger}).json()
    assert snap["items"] == []  # 另一账本互不可见
    assert snap["serverRevision"] == 0


def test_missing_sync_id_rejected(client):
    tc, code = client
    p = pair(tc, code)
    item = bill_item("i" + "0" * 16, "x", epoch=p["epoch"])
    item["payload"] = {"priceCents": 1}  # 缺 syncId
    r = push(tc, p, [item])
    assert r.status_code == 422


def test_invalid_ledger_id(client):
    tc, code = client
    p = pair(tc, code)
    r = tc.get("/v1/ledger/snapshot", headers=hdr(p), params={"ledgerId": "not-a-uuid"})
    assert r.status_code == 422


# ── W3: 跨设备按需导入来源快照 ──────────────────────────────────
def _fresh_code(tc) -> str:
    return tc.post("/v1/pair/code").json()["code"] if False else _issue_code()


def _issue_code() -> str:
    from app.pairing import PAIRING
    return PAIRING.issue()


def device_id_of(p: dict) -> str:
    # pair() 返回体里带 deviceId（见 server pairing），测试里直接取
    return p["deviceId"]


def test_import_source_snapshot_cross_device(client):
    tc, code = client
    a = pair(tc, code, name="A 手机")
    # 配对码一次性：B 用新码（同样落入同 space，因为服务是单 space 部署）
    b = pair(tc, _fresh_code(tc), name="B 手机")  # 同 space，不同设备

    # A 往 A 账本写两条（同一次读取配对后 A 有自己的 ledger_id）
    a_ledger = str(uuid.uuid4())
    sid = str(uuid.uuid4())
    def push_a(items):
        return tc.post("/v1/ledger/push", headers=hdr(a),
                       json={"protocolVersion": 1, "spaceId": a["spaceId"], "ledgerId": a_ledger,
                             "epoch": a["epoch"], "items": items})
    item = bill_item("j" + "0" * 16, sid, epoch=a["epoch"])
    item["ledgerId"] = a_ledger
    assert push_a([item]).json()["items"][0]["status"] == "ok"

    # A 删一条，制造墓碑，B 导入时也要看到删除标记
    sid2 = str(uuid.uuid4())
    item2 = bill_item("k" + "0" * 16, sid2, epoch=a["epoch"])
    item2["ledgerId"] = a_ledger
    push_a([item2])
    # 用当前修订删除 sid2
    cur = tc.get("/v1/ledger/snapshot", headers=hdr(a), params={"ledgerId": a_ledger}).json()["serverRevision"]
    del_item = bill_item("l" + "0" * 16, sid2, base_rev=cur, epoch=a["epoch"], operation="delete")
    del_item["ledgerId"] = a_ledger
    push_a([del_item])

    # B 读取 A 的冻结来源快照
    r = tc.get("/v1/ledger/import/source", headers=hdr(b),
               params={"sourceDeviceId": device_id_of(a), "sourceLedgerId": a_ledger})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["sourceRevision"] == 3
    assert len(body["items"]) == 2
    assert body["importSnapshotId"]
    deleted = [i for i in body["items"] if i["payload"]["syncId"] == sid2][0]
    assert deleted.get("deletedAt") is not None  # 墓碑随快照带出

    # B 不能再从自己导入
    r_self = tc.get("/v1/ledger/import/source", headers=hdr(b),
                    params={"sourceDeviceId": device_id_of(b), "sourceLedgerId": str(uuid.uuid4())})
    assert r_self.status_code == 422

    # 不存在的来源设备 → 404
    r_missing = tc.get("/v1/ledger/import/source", headers=hdr(b),
                       params={"sourceDeviceId": str(uuid.uuid4()), "sourceLedgerId": a_ledger})
    assert r_missing.status_code == 404


def test_list_import_sources(client):
    tc, code = client
    a = pair(tc, code, name="A 手机")
    b = pair(tc, _fresh_code(tc), name="B 手机")
    a_ledger = str(uuid.uuid4())
    sid = str(uuid.uuid4())
    item = bill_item("m" + "0" * 16, sid, epoch=a["epoch"])
    item["ledgerId"] = a_ledger
    tc.post("/v1/ledger/push", headers=hdr(a),
            json={"protocolVersion": 1, "spaceId": a["spaceId"], "ledgerId": a_ledger,
                  "epoch": a["epoch"], "items": [item]})
    # B 看到 A 可导入，看不到自己
    body = tc.get("/v1/ledger/import/sources", headers=hdr(b)).json()
    ids = [s["deviceId"] for s in body["sources"]]
    assert device_id_of(a) in ids
    assert device_id_of(b) not in ids
    src = [s for s in body["sources"] if s["deviceId"] == device_id_of(a)][0]
    assert src["ledgerId"] == a_ledger
    assert src["itemCount"] == 1
