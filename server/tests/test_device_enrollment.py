from __future__ import annotations

import hashlib
import json
import os
import secrets
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.migrate import apply_schema


@pytest.fixture()
def client(tmp_path, monkeypatch):
    database_url = os.environ["MYMONEY_TEST_DATABASE_URL"]
    schema = "mymoney_device_enrollment_test"
    monkeypatch.setenv("MYMONEY_DATABASE_URL", database_url)
    monkeypatch.setenv("MYMONEY_DB_SCHEMA", schema)
    monkeypatch.setenv("MYMONEY_BACKUP_DIR", str(tmp_path))
    monkeypatch.delenv("MYMONEY_PAIRING_CODE", raising=False)
    get_settings.cache_clear()
    with psycopg.connect(database_url) as conn:
        conn.execute(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE')
        apply_schema(conn, schema)
    from app.main import app
    with TestClient(app) as c:
        yield c, tmp_path
    with psycopg.connect(database_url) as conn:
        conn.execute(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE')
    get_settings.cache_clear()


def issue():
    from app.db import connection
    from app.device_enrollment import issue_connection_code
    with connection() as conn:
        return issue_connection_code(conn)["code"]


def grant(c, code=None, installation=None, operation=None):
    request = {"code": code or issue(), "installationId": installation or str(uuid4()),
               "operationId": operation or str(uuid4())}
    response = c.post("/v1/device-enrollment/authorize", json=request)
    assert response.status_code == 200
    return request, response.json()


def complete_body(request, source=None):
    return {"installationId": request["installationId"], "operationId": request["operationId"],
            "credential": secrets.token_urlsafe(32), "deviceName": "同型号手机",
            "sourceId": source["deviceId"] if source else None,
            "expectedGeneration": source["credentialGeneration"] if source else None}


def complete(c, authorization, body):
    return c.post("/v1/device-enrollment/complete", headers={"Authorization": "Bearer " + authorization["grant"]}, json=body)


def enrolled(c):
    request, authorization = grant(c)
    body = complete_body(request)
    response = complete(c, authorization, body)
    assert response.status_code == 200
    return response.json(), {"Authorization": "Bearer " + body["credential"]}, (request, authorization, body)


def count():
    from app.db import connection
    with connection() as conn:
        return conn.execute("SELECT count(*) AS n FROM devices").fetchone()["n"]


def test_same_named_devices_and_retries_do_not_create_duplicates(client):
    c, _ = client
    capability = c.get("/v1/backup-capabilities").json()
    assert capability["deviceIdentityVersion"] == 1 and capability["ownBackupsOnly"] is True
    assert capability["deleteBackups"] is True and capability["backupContext"] is True
    request, authorization = grant(c)
    assert c.post("/v1/device-enrollment/authorize", json=request).json() == authorization
    body = complete_body(request)
    first = complete(c, authorization, body)
    assert first.status_code == 200
    assert complete(c, authorization, body).json() == first.json()
    for _ in range(3):
        assert c.get("/v1/devices/self", headers={"Authorization": "Bearer " + body["credential"]}).json()["deviceId"] == first.json()["deviceId"]
    other, _, _ = enrolled(c)
    assert other["deviceId"] != first.json()["deviceId"] and count() == 2
    denied = c.post("/v1/device-enrollment/authorize", json=dict(request,installationId=str(uuid4())))
    assert denied.status_code == 401


def test_expired_codes_and_grants_or_space_epoch_cannot_authorize(client):
    from app.db import connection
    from app.auth import hash_token
    c, _ = client
    code = issue()
    with connection() as conn:
        conn.execute("UPDATE device_connection_codes SET expires_at=now()-interval '1 second' WHERE code_hash=%s",(hash_token(code),));conn.commit()
    assert c.post("/v1/device-enrollment/authorize",json={"code":code,"installationId":str(uuid4()),"operationId":str(uuid4())}).status_code == 401
    request, authorization = grant(c)
    with connection() as conn:
        conn.execute("UPDATE device_connection_codes SET grant_expires_at=now()-interval '1 second'");conn.commit()
    assert complete(c,authorization,complete_body(request)).status_code == 401
    request, authorization = grant(c)
    with connection() as conn:
        conn.execute("UPDATE sync_spaces SET epoch=%s",(uuid4(),));conn.commit()
    assert complete(c,authorization,complete_body(request)).status_code == 401
    assert count() == 0


def test_claim_rotates_credentials_but_retains_id_and_backups(client):
    c, directory = client
    a, old_headers, _ = enrolled(c)
    b, b_headers, _ = enrolled(c)
    raw = json.dumps({"kind":"MyMoneyBackup","format":10,"exportedAt":1793520000000,"bills":[]}).encode()
    backup_id = str(uuid4())
    upload_headers = dict(old_headers,**{"Content-Type":"application/json","X-Content-SHA256":hashlib.sha256(raw).hexdigest(),"X-Space-Epoch":a["epoch"],"X-App-Version":"1.9.0/153"})
    assert c.put(f"/v1/backups/{backup_id}",content=raw,headers=upload_headers).status_code == 200
    request, authorization = grant(c)
    choices = c.get("/v1/device-enrollment/sources",headers={"Authorization":"Bearer "+authorization["grant"]}).json()["items"]
    assert {r["deviceId"] for r in choices} == {a["deviceId"],b["deviceId"]}
    assert all("token" not in row and "backupId" not in row for row in choices)
    body = complete_body(request,a)
    replacement = complete(c,authorization,body)
    assert replacement.status_code == 200
    assert replacement.json()["deviceId"] == a["deviceId"]
    assert replacement.json()["credentialGeneration"] == a["credentialGeneration"]+1
    new_headers = {"Authorization":"Bearer "+body["credential"]}
    assert c.get("/v1/devices/self",headers=old_headers).status_code == 401
    assert c.get("/v1/devices/self",headers=b_headers).json()["deviceId"] == b["deviceId"]
    assert c.get(f"/v1/backups/{backup_id}",headers=new_headers).content == raw
    assert c.get(f"/v1/backups/{backup_id}",headers=b_headers).status_code == 404
    assert count() == 2
    assert complete(c,authorization,body).json() == replacement.json()
    request2, authorization2 = grant(c)
    body2 = complete_body(request2,replacement.json())
    assert complete(c,authorization2,body2).status_code == 200
    assert complete(c,authorization,body).status_code == 409
    assert c.get("/v1/devices/self",headers=new_headers).status_code == 401


def test_uuid_alone_and_wrong_install_operation_never_take_over(client):
    c, _ = client
    a, headers, saved = enrolled(c)
    request, authorization = grant(c,installation=saved[0]["installationId"])
    assert complete(c,authorization,complete_body(request)).status_code == 409
    request2, authorization2 = grant(c)
    body = complete_body(request2,a)
    assert complete(c,authorization2,dict(body,operationId=str(uuid4()))).status_code == 403
    assert complete(c,authorization2,dict(body,sourceId=str(uuid4()))).status_code == 404
    assert complete(c,authorization2,dict(body,expectedGeneration=a["credentialGeneration"]+1)).status_code == 409
    assert count()==1 and c.get("/v1/devices/self",headers=headers).status_code==200


def test_duplicate_request_concurrency_and_competing_claims(client):
    c, _ = client
    request, authorization = grant(c)
    body = complete_body(request)
    with ThreadPoolExecutor(max_workers=2) as pool:
        results=list(pool.map(lambda _: complete(c,authorization,body),range(2)))
    assert [r.status_code for r in results]==[200,200]
    a=results[0].json();assert results[1].json()==a and count()==1
    r1,g1=grant(c);r2,g2=grant(c)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures=[pool.submit(complete,c,g1,complete_body(r1,a)),pool.submit(complete,c,g2,complete_body(r2,a))]
        results=[f.result() for f in futures]
    assert sorted(r.status_code for r in results)==[200,409] and count()==1


def test_legacy_identity_binding_rename_and_revoke(client):
    from app.pairing import PAIRING
    c, _ = client
    legacy = c.post('/v1/pair',json={"code":PAIRING.issue(),"deviceName":"old"}).json()
    headers={"Authorization":"Bearer "+legacy["token"]}
    installation=str(uuid4())
    response=c.post('/v1/devices/self/bind-installation',json={"installationId":installation},headers=headers)
    assert response.status_code==200 and response.json()["deviceId"]==legacy["deviceId"]
    assert c.post('/v1/devices/self/bind-installation',json={"installationId":installation},headers=headers).status_code==200
    assert c.post('/v1/devices/self/bind-installation',json={"installationId":str(uuid4())},headers=headers).status_code==409
    renamed=c.post('/v1/devices/self/rename',json={"name":"日用机"},headers=headers)
    assert renamed.json()["name"]=="日用机" and count()==1
    assert c.post('/v1/device/revoke',headers=headers).status_code==200
    assert c.get('/v1/devices/self',headers=headers).status_code==401


def test_secrets_not_stored_in_plaintext_and_grant_consumed_once(client):
    from app.db import connection
    c, _ = client
    request, authorization = grant(c)
    body=complete_body(request)
    response=complete(c,authorization,body)
    assert response.status_code==200
    assert complete(c,authorization,dict(body,deviceName="different")).status_code==409
    with connection() as conn:
        rows=[]
        for table in ['device_connection_codes','device_enrollment_operations','devices']:
            rows.extend(str(r) for r in conn.execute('SELECT * FROM '+table).fetchall())
    stored=' '.join(rows)
    assert all(secret not in stored for secret in [request['code'],authorization['grant'],body['credential']])
    assert c.get('/v1/device-enrollment/sources',headers={"Authorization":"Bearer "+authorization['grant']}).status_code==409


def test_claim_requires_fresh_credential_and_cross_space_is_rejected(client):
    from app.db import connection
    c, _ = client
    source, headers, saved = enrolled(c)
    request, authorization = grant(c)
    body = complete_body(request,source)
    assert complete(c,authorization,dict(body,credential=saved[2]["credential"])).status_code == 409
    other_id, other_space = uuid4(), uuid4()
    with connection() as conn:
        conn.execute("INSERT INTO sync_spaces(id,epoch,name) VALUES (%s,%s,'other')",(other_space,uuid4()))
        conn.execute("INSERT INTO devices(space_id,id,name,token_hash) VALUES (%s,%s,'other',%s)",
                     (other_space,other_id,hashlib.sha256(secrets.token_bytes(32)).hexdigest()))
        conn.commit()
    assert complete(c,authorization,dict(body,sourceId=str(other_id))).status_code == 404
    assert c.get('/v1/devices/self',headers=headers).status_code == 200


def test_incremental_schema_is_repeatable_and_preserves_existing_backup_identity(client):
    from app.db import connection
    c, _ = client
    original, headers, saved = enrolled(c)
    with connection() as conn:
        apply_schema(conn,get_settings().schema)
        apply_schema(conn,get_settings().schema)
    assert c.get('/v1/devices/self',headers=headers).json() == original
    assert count() == 1
