mod common;

use std::fs;
use std::sync::Arc;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use serde_json::{json, Value};
use sqlx::Row;
use uuid::Uuid;

use mymoney_home::auth::{hash_token, sha256_bytes};
use mymoney_home::backup::storage::{audit_files, recycle_pending_backups};
use mymoney_home::backup::validation::validate_snapshot;
use mymoney_home::config::Settings;
use mymoney_home::connect_code::{build_connect_code, render_qr_svg};
use mymoney_home::db::apply_schema;
use mymoney_home::pairing::PairingStore;

use common::TestEnv;

#[test]
fn config_requires_explicit_database_url() {
    std::env::remove_var("MYMONEY_DATABASE_URL");
    std::env::set_var("MYMONEY_DB_SCHEMA", "mymoney_home_test");
    assert!(Settings::from_env().is_err());

    std::env::set_var("MYMONEY_DATABASE_URL", "   ");
    assert!(Settings::from_env().is_err());
    std::env::remove_var("MYMONEY_DATABASE_URL");
    std::env::remove_var("MYMONEY_DB_SCHEMA");
}

#[test]
fn connect_code_v2_and_qr_validation() {
    let code = build_connect_code(
        "https://controlplane.tailscale.com",
        "tskey-auth-kTEST-abc",
        "http://100.65.248.62:18080/",
        "ABC123XY",
        "小米14",
    )
    .expect("valid connect code");
    assert!(code.starts_with("MMJOIN2."));
    let raw = URL_SAFE_NO_PAD
        .decode(&code["MMJOIN2.".len()..])
        .expect("decode base64url");
    let payload: Value = serde_json::from_slice(&raw).expect("json");
    assert_eq!(payload["v"], 2);
    assert_eq!(payload["key"], "tskey-auth-kTEST-abc");
    assert_eq!(payload["server"], "http://100.65.248.62:18080");
    assert_eq!(payload["code"], "ABC123XY");
    assert_eq!(payload["name"], "小米14");

    assert!(build_connect_code("https://c", "abc", "http://s", "X", "").is_err());
    assert!(build_connect_code("https://c", "tskey-a", "http://s", "  ", "").is_err());

    let svg = render_qr_svg(&code).expect("qr svg");
    assert!(svg.contains("<svg"));
    assert!(render_qr_svg(&"x".repeat(5000)).is_none());
}

#[test]
fn pairing_store_one_time_and_permanent() {
    let store = PairingStore::new(None, 300);
    let c = store.issue(None);
    assert!(store.verify(&c));
    assert!(!store.verify(&c));

    let perm = store.issue(Some(0));
    assert!(store.verify(&perm));
    assert!(store.verify(&perm.to_lowercase()));
}

#[test]
fn backup_snapshot_and_deletion_validation() {
    let tmp = tempfile::TempDir::new().unwrap();
    let validate_val = |v: &Value| {
        let p = tmp.path().join("snap.json");
        fs::write(&p, serde_json::to_vec(v).unwrap()).unwrap();
        validate_snapshot(&p)
    };

    for ver in [8, 9, 10, 11, 12] {
        let mut snap = json!({
            "kind": "MyMoneyBackup",
            "format": ver,
            "exportedAt": 1700000000002i64,
            "bills": [],
        });
        if ver >= 9 {
            snap["deletions"] = json!([]);
        }
        let (cnt, _, schema) = validate_val(&snap).expect("valid snapshot");
        assert_eq!(cnt, 0);
        assert_eq!(schema, ver);
    }

    // Invalid format 13
    let bad =
        json!({"kind": "MyMoneyBackup", "format": 13, "exportedAt": 1700000000002i64, "bills": []});
    assert!(validate_val(&bad).is_err());

    // Duplicate syncId in format >= 10
    let first = json!({"p": 100, "ty": "EXPENSE", "t": 1, "fp": "a", "sp": "test", "st": "test", "sid": "dup-id"});
    let dup = json!({"kind": "MyMoneyBackup", "format": 10, "exportedAt": 1700000000002i64, "bills": [first, first]});
    assert!(validate_val(&dup).is_err());

    // Valid transfer deletion with fee
    let principal = json!({
        "id": 1, "priceCents": 1000, "type": "TRANSFER", "timeMillis": 1700000000000i64,
        "sourcePkg": "sim", "strategy": "test", "fingerprint": "fp", "createdAt": 1700000000000i64,
        "assetKey": "tx-key", "assetLinked": true, "transferFeeCents": 50,
        "accountId": "acc-1", "transferAccountId": "acc-2"
    });
    let fee = json!({
        "id": 2, "priceCents": 50, "type": "EXPENSE", "timeMillis": 1700000000000i64,
        "sourcePkg": "sim", "strategy": "test", "fingerprint": "fp2", "createdAt": 1700000000000i64,
        "assetKey": "fee-key", "assetLinked": true, "transferFeeCents": 0,
        "accountId": "acc-1", "feeParentKey": "tx-key"
    });
    let valid_del = json!({
        "kind": "MyMoneyBackup",
        "format": 9,
        "exportedAt": 1700000000002i64,
        "bills": [],
        "deletions": [{
            "eventId": Uuid::new_v4().to_string(),
            "deletedAt": 1700000000001i64,
            "origin": "bill_detail",
            "principalId": 1,
            "recognitionId": null,
            "groupId": "tx-key",
            "snapshotVersion": 1,
            "snapshotJson": serde_json::to_string(&json!({"bills": [principal, fee]})).unwrap()
        }]
    });
    assert_eq!(validate_val(&valid_del).unwrap().0, 0);

    // Whitespace assetKey linking fee must fail
    let bad_p = json!({
        "id": 1, "priceCents": 1000, "type": "TRANSFER", "timeMillis": 1700000000000i64,
        "sourcePkg": "sim", "strategy": "test", "fingerprint": "fp", "createdAt": 1700000000000i64,
        "assetKey": " ", "assetLinked": false, "transferFeeCents": 50, "accountId": "acc-1"
    });
    let bad_f = json!({
        "id": 2, "priceCents": 50, "type": "EXPENSE", "timeMillis": 1700000000000i64,
        "sourcePkg": "sim", "strategy": "test", "fingerprint": "fp2", "createdAt": 1700000000000i64,
        "assetKey": "fee", "assetLinked": false, "transferFeeCents": 0, "accountId": "acc-1", "feeParentKey": " "
    });
    let bad_del = json!({
        "kind": "MyMoneyBackup",
        "format": 9,
        "exportedAt": 1700000000002i64,
        "bills": [],
        "deletions": [{
            "eventId": Uuid::new_v4().to_string(),
            "deletedAt": 1700000000001i64,
            "origin": "detail",
            "principalId": 1,
            "recognitionId": null,
            "groupId": "bill:1",
            "snapshotVersion": 1,
            "snapshotJson": serde_json::to_string(&json!({"bills": [bad_p, bad_f]})).unwrap()
        }]
    });
    assert!(validate_val(&bad_del).is_err());
}

#[tokio::test]
async fn rule_sync_pair_push_conflict_pull_delete() {
    let env = TestEnv::new().await;
    let (st, _, hval) = env.json_req(Method::GET, "/health", &[], None).await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(hval["ok"], true);

    let code = env.state.pairing.issue(None);
    let (st, _, paired) = env
        .json_req(
            Method::POST,
            "/v1/pair",
            &[],
            Some(json!({"code": code, "deviceName": "测试手机"})),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    let token = paired["token"].as_str().unwrap().to_string();
    let epoch = paired["epoch"].as_str().unwrap().to_string();
    let space_id = paired["spaceId"].as_str().unwrap().to_string();
    let auth = format!("Bearer {}", token);

    // One-time code cannot be reused
    let (st, _, _) = env
        .json_req(
            Method::POST,
            "/v1/pair",
            &[],
            Some(json!({"code": code, "deviceName": "第二台"})),
        )
        .await;
    assert_eq!(st, StatusCode::UNAUTHORIZED);

    let make_item = |op: &str, base_rev: i64, operation: &str, payload: Option<Value>| {
        json!({
            "operationId": op,
            "epoch": epoch,
            "itemId": "personal_test123",
            "baseRevision": base_rev,
            "operation": operation,
            "kind": "recognition_rule",
            "schemaVersion": 1,
            "contentUpdatedAt": 100,
            "sortKey": "1000",
            "naturalKey": "personal_test123",
            "payload": payload,
        })
    };
    let rule_payload = json!({
        "id": "personal_test123", "name": "测试", "app": "com.tencent.mm",
        "inputType": "PAGE", "billType": "Expend", "keyword": "测试",
        "amountSource": "AUTO", "merchant": "", "method": "", "enabled": true, "updatedAt": 100
    });

    let item1 = make_item("op-0000000000000001", 0, "put", Some(rule_payload.clone()));
    let (st, _, push1) = env
        .json_req(
            Method::POST,
            "/v1/sync/push",
            &[("authorization", &auth)],
            Some(json!({"protocolVersion": 1, "spaceId": space_id, "epoch": epoch, "items": [item1]})),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(push1["items"][0]["status"], "ok");
    assert_eq!(push1["items"][0]["revision"], 1);

    // Idempotent replay
    let (st, _, replay) = env
        .json_req(
            Method::POST,
            "/v1/sync/push",
            &[("authorization", &auth)],
            Some(json!({"protocolVersion": 1, "spaceId": space_id, "epoch": epoch, "items": [item1]})),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(replay["items"][0]["revision"], 1);

    // Stale base revision -> conflict
    let stale = make_item("op-0000000000000002", 0, "put", Some(rule_payload.clone()));
    let (st, _, stale_res) = env
        .json_req(
            Method::POST,
            "/v1/sync/push",
            &[("authorization", &auth)],
            Some(json!({"protocolVersion": 1, "spaceId": space_id, "epoch": epoch, "items": [stale]})),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(stale_res["items"][0]["status"], "conflict");
    assert_eq!(stale_res["items"][0]["remoteRevision"], 1);

    // Pull & Ack
    let (st, _, pull_res) = env
        .json_req(
            Method::GET,
            "/v1/sync/pull?after=0",
            &[("authorization", &auth)],
            None,
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(pull_res["nextCursor"], 1);

    let (st, _, _) = env
        .json_req(
            Method::POST,
            "/v1/sync/ack",
            &[("authorization", &auth)],
            Some(json!({"throughRevision": 1, "epoch": epoch})),
        )
        .await;
    assert_eq!(st, StatusCode::OK);

    // Delete & tombstone conflict
    let del = make_item("op-0000000000000003", 1, "delete", None);
    let (st, _, del_res) = env
        .json_req(
            Method::POST,
            "/v1/sync/push",
            &[("authorization", &auth)],
            Some(
                json!({"protocolVersion": 1, "spaceId": space_id, "epoch": epoch, "items": [del]}),
            ),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert!(del_res["items"][0]["snapshot"]["deletedAt"].is_string());

    let revive_put = make_item("op-0000000000000004", 2, "put", Some(rule_payload));
    let (st, _, rev_res) = env
        .json_req(
            Method::POST,
            "/v1/sync/push",
            &[("authorization", &auth)],
            Some(json!({"protocolVersion": 1, "spaceId": space_id, "epoch": epoch, "items": [revive_put]})),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(rev_res["items"][0]["status"], "conflict");

    env.cleanup().await;
}

#[tokio::test]
async fn ledger_sync_and_cross_device_import_parity() {
    let env = TestEnv::new().await;
    let (_, _, a) = env
        .json_req(
            Method::POST,
            "/v1/pair",
            &[],
            Some(json!({"code": env.state.pairing.issue(None), "deviceName": "A 手机"})),
        )
        .await;
    let (_, _, b) = env
        .json_req(
            Method::POST,
            "/v1/pair",
            &[],
            Some(json!({"code": env.state.pairing.issue(None), "deviceName": "B 手机"})),
        )
        .await;

    let a_auth = format!("Bearer {}", a["token"].as_str().unwrap());
    let b_auth = format!("Bearer {}", b["token"].as_str().unwrap());
    let a_ledger = Uuid::new_v4().to_string();
    let sid1 = Uuid::new_v4().to_string();
    let sid2 = Uuid::new_v4().to_string();

    let mk = |op: &str, sid: &str, rev: i64, op_type: &str, amount: i64| {
        json!({
            "operationId": op,
            "epoch": a["epoch"],
            "ledgerId": a_ledger,
            "itemId": format!("bill:{}", sid),
            "itemKind": "bill",
            "baseRevision": rev,
            "operation": op_type,
            "schemaVersion": 1,
            "contentUpdatedAt": 100,
            "payload": {"syncId": sid, "priceCents": amount, "type": "EXPENSE"},
        })
    };

    let item1 = mk("op_0000000000000001", &sid1, 0, "put", 100);
    let (st, _, res1) = env
        .json_req(
            Method::POST,
            "/v1/ledger/push",
            &[("authorization", &a_auth)],
            Some(json!({"protocolVersion": 1, "spaceId": a["spaceId"], "ledgerId": a_ledger, "epoch": a["epoch"], "items": [item1]})),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(res1["items"][0]["revision"], 1);

    // Same operationId different amount -> 409
    let conflict_op = mk("op_0000000000000001", &sid1, 0, "put", 999);
    let (st, _, err_body) = env
        .json_req(
            Method::POST,
            "/v1/ledger/push",
            &[("authorization", &a_auth)],
            Some(json!({"protocolVersion": 1, "spaceId": a["spaceId"], "ledgerId": a_ledger, "epoch": a["epoch"], "items": [conflict_op]})),
        )
        .await;
    assert_eq!(st, StatusCode::CONFLICT);
    assert!(err_body["error"].is_string());

    // Push & delete second item
    let item2 = mk("op_0000000000000002", &sid2, 0, "put", 200);
    let (st, _, _) = env
        .json_req(
            Method::POST,
            "/v1/ledger/push",
            &[("authorization", &a_auth)],
            Some(json!({"protocolVersion": 1, "spaceId": a["spaceId"], "ledgerId": a_ledger, "epoch": a["epoch"], "items": [item2]})),
        )
        .await;
    assert_eq!(st, StatusCode::OK);

    let del2 = mk("op_0000000000000003", &sid2, 2, "delete", 200);
    let (st, _, _) = env
        .json_req(
            Method::POST,
            "/v1/ledger/push",
            &[("authorization", &a_auth)],
            Some(json!({"protocolVersion": 1, "spaceId": a["spaceId"], "ledgerId": a_ledger, "epoch": a["epoch"], "items": [del2]})),
        )
        .await;
    assert_eq!(st, StatusCode::OK);

    // B lists import sources & imports frozen snapshot from A
    let (st, _, srcs) = env
        .json_req(
            Method::GET,
            "/v1/ledger/import/sources",
            &[("authorization", &b_auth)],
            None,
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(srcs["sources"][0]["deviceId"], a["deviceId"]);

    let url = format!(
        "/v1/ledger/import/source?sourceDeviceId={}&sourceLedgerId={}",
        a["deviceId"].as_str().unwrap(),
        a_ledger
    );
    let (st, _, imported) = env
        .json_req(Method::GET, &url, &[("authorization", &b_auth)], None)
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(imported["sourceRevision"], 3);
    assert_eq!(imported["items"].as_array().unwrap().len(), 2);

    // Cannot import from self -> 422
    let self_url = format!(
        "/v1/ledger/import/source?sourceDeviceId={}&sourceLedgerId={}",
        b["deviceId"].as_str().unwrap(),
        Uuid::new_v4()
    );
    let (st, _, _) = env
        .json_req(Method::GET, &self_url, &[("authorization", &b_auth)], None)
        .await;
    assert_eq!(st, StatusCode::UNPROCESSABLE_ENTITY);

    env.cleanup().await;
}

#[tokio::test]
async fn backups_upload_download_delete_recycle_and_audit_parity() {
    let env = TestEnv::new().await;
    let (_, _, p) = env
        .json_req(
            Method::POST,
            "/v1/pair",
            &[],
            Some(json!({"code": env.state.pairing.issue(None), "deviceName": "快照设备"})),
        )
        .await;
    let auth = format!("Bearer {}", p["token"].as_str().unwrap());
    let epoch = p["epoch"].as_str().unwrap().to_string();
    let space_id = p["spaceId"].as_str().unwrap().to_string();

    let raw = serde_json::to_vec(&json!({
        "kind": "MyMoneyBackup",
        "format": 10,
        "exportedAt": 1793520000000i64,
        "bills": [{"p": 123, "ty": "REFUND", "t": 1, "fp": "f", "sp": "", "st": "t", "sid": "s1"}],
        "history": []
    }))
    .unwrap();
    let sha = sha256_bytes(&raw);
    let bid1 = Uuid::new_v4().to_string();

    let put_req = |id: &str, bytes: Vec<u8>, sha_hdr: &str, ep: &str| {
        Request::builder()
            .method(Method::PUT)
            .uri(format!("/v1/backups/{}", id))
            .header("authorization", &auth)
            .header("content-type", "application/json")
            .header("content-length", bytes.len().to_string())
            .header("x-content-sha256", sha_hdr)
            .header("x-space-epoch", ep)
            .header("x-app-version", "1.10.3/162")
            .body(Body::from(bytes))
            .unwrap()
    };

    // Missing Content-Length -> 411; oversized Content-Length -> 413
    let (st_no_len, _, _) = env
        .send(
            Request::builder()
                .method(Method::PUT)
                .uri(format!("/v1/backups/{}", bid1))
                .header("authorization", &auth)
                .header("content-type", "application/json")
                .header("x-content-sha256", &sha)
                .header("x-space-epoch", &epoch)
                .header("x-app-version", "1.10.3/162")
                .body(Body::from(raw.clone()))
                .unwrap(),
        )
        .await;
    assert_eq!(st_no_len, StatusCode::LENGTH_REQUIRED);

    let (st_too_big, _, _) = env
        .send(
            Request::builder()
                .method(Method::PUT)
                .uri(format!("/v1/backups/{}", bid1))
                .header("authorization", &auth)
                .header("content-type", "application/json")
                .header("content-length", (201u64 * 1024 * 1024).to_string())
                .header("x-content-sha256", &sha)
                .header("x-space-epoch", &epoch)
                .header("x-app-version", "1.10.3/162")
                .body(Body::from(raw.clone()))
                .unwrap(),
        )
        .await;
    assert_eq!(st_too_big, StatusCode::PAYLOAD_TOO_LARGE);

    // Valid upload
    let (st, _, up1_bytes) = env.send(put_req(&bid1, raw.clone(), &sha, &epoch)).await;
    assert_eq!(st, StatusCode::OK);
    let up1: Value = serde_json::from_slice(&up1_bytes).unwrap();
    assert_eq!(up1["billCount"], 1);
    assert_eq!(up1["backupSchema"], 10);

    // Idempotent retry
    let (st, _, up1_retry) = env.send(put_req(&bid1, raw.clone(), &sha, &epoch)).await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(serde_json::from_slice::<Value>(&up1_retry).unwrap(), up1);

    // Same id, different content -> 409
    let changed = serde_json::to_vec(&json!({
        "kind": "MyMoneyBackup", "format": 10, "exportedAt": 1793520000000i64, "bills": []
    }))
    .unwrap();
    let (st, _, _) = env
        .send(put_req(
            &bid1,
            changed.clone(),
            &sha256_bytes(&changed),
            &epoch,
        ))
        .await;
    assert_eq!(st, StatusCode::CONFLICT);

    // Corrupt file on disk -> 409 on download -> same id upload repairs it
    let disk_path = env
        .backup_dir
        .path()
        .join(&space_id)
        .join(format!("{}.json", bid1));
    fs::write(&disk_path, b"corrupt").unwrap();
    let (st, _, _) = env
        .json_req(
            Method::GET,
            &format!("/v1/backups/{}", bid1),
            &[("authorization", &auth)],
            None,
        )
        .await;
    assert_eq!(st, StatusCode::CONFLICT);
    let (st, _, _) = env.send(put_req(&bid1, raw.clone(), &sha, &epoch)).await;
    assert_eq!(st, StatusCode::OK);
    let (st, hdrs, dl_bytes) = env
        .send(
            Request::builder()
                .method(Method::GET)
                .uri(format!("/v1/backups/{}", bid1))
                .header("authorization", &auth)
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(dl_bytes, raw);
    assert_eq!(hdrs.get("x-content-sha256").unwrap().to_str().unwrap(), sha);

    // Delete & tombstone & 410 re-upload rejection
    let op_id = Uuid::new_v4().to_string();
    let (st, _, del_res) = env
        .json_req(
            Method::POST,
            "/v1/backups/delete",
            &[("authorization", &auth)],
            Some(json!({"operationId": op_id, "backupIds": [bid1]})),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(del_res["deletedCount"], 1);
    assert_eq!(del_res["recyclePending"], false);
    assert!(!disk_path.exists());

    // Idempotent delete
    let (st, _, del_replay) = env
        .json_req(
            Method::POST,
            "/v1/backups/delete",
            &[("authorization", &auth)],
            Some(json!({"operationId": op_id, "backupIds": [bid1]})),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(del_replay["deletedCount"], 1);

    // Re-upload deleted backup id -> 410 Gone
    let (st, _, _) = env.send(put_req(&bid1, raw.clone(), &sha, &epoch)).await;
    assert_eq!(st, StatusCode::GONE);

    // Audit files check
    let orphan = env
        .backup_dir
        .path()
        .join(&space_id)
        .join(format!("{}.json", Uuid::new_v4()));
    fs::write(&orphan, &raw).unwrap();
    let audit = audit_files(&env.pool, env.backup_dir.path()).await.unwrap();
    assert_eq!(audit["orphanFiles"].as_array().unwrap().len(), 1);
    assert_eq!(audit["deletedCount"], 1);
    let _ = recycle_pending_backups(&env.pool, env.backup_dir.path())
        .await
        .unwrap();

    env.cleanup().await;
}

#[tokio::test]
async fn enrollment_concurrency_claim_and_admin_security_parity() {
    let env = Arc::new(TestEnv::new().await);
    let admin_hdr = [("x-admin-token", env.admin_token.as_str())];

    // Admin page security headers & no secrets / no localStorage
    let (st, hdrs, page_bytes) = env
        .send(
            Request::builder()
                .method(Method::GET)
                .uri("/admin/cloud-backup")
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(hdrs.get("cache-control").unwrap(), "no-store");
    assert_eq!(hdrs.get("x-frame-options").unwrap(), "DENY");
    assert_eq!(hdrs.get("pragma").unwrap(), "no-cache");
    let csp = hdrs
        .get("content-security-policy")
        .unwrap()
        .to_str()
        .unwrap();
    assert!(!csp.contains("unsafe-inline"));
    let page_text = String::from_utf8_lossy(&page_bytes);
    assert!(!page_text.contains(&env.admin_token));
    assert!(!page_text.contains(&env.state.settings.tskey));
    assert!(!page_text.contains("localStorage"));

    // /admin redirects to /admin/cloud-backup
    let (st, hdrs, _) = env
        .send(
            Request::builder()
                .method(Method::GET)
                .uri("/admin")
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    assert!(st.is_redirection());
    assert_eq!(hdrs.get("location").unwrap(), "/admin/cloud-backup");

    // Unauthorized admin calls -> 403
    let (st, _, _) = env
        .json_req(Method::GET, "/v1/admin/backup-settings", &[], None)
        .await;
    assert_eq!(st, StatusCode::FORBIDDEN);

    // Issue MMJOIN2 connect code + QR
    let (st, _, cc) = env
        .json_req(
            Method::POST,
            "/v1/admin/connect-code",
            &admin_hdr,
            Some(json!({"deviceName": "小米14"})),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(cc["qrEncodable"], true);
    assert!(cc["qrSvg"].as_str().unwrap().contains("<svg"));
    let inv_id = cc["invitationId"].as_str().unwrap().to_string();
    let encoded = cc["connectCode"].as_str().unwrap();
    let raw = URL_SAFE_NO_PAD
        .decode(&encoded["MMJOIN2.".len()..])
        .unwrap();
    let payload: Value = serde_json::from_slice(&raw).unwrap();
    let pair_code = payload["code"].as_str().unwrap().to_string();

    // Status lifecycle pending -> authorized -> completed
    let (st, _, status_pending) = env
        .json_req(
            Method::GET,
            &format!("/v1/admin/connect-codes/{}", inv_id),
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(status_pending["status"], "pending");

    let inst = Uuid::new_v4().to_string();
    let op = Uuid::new_v4().to_string();
    let (st, _, grant_res) = env
        .json_req(
            Method::POST,
            "/v1/device-enrollment/authorize",
            &[],
            Some(json!({"code": pair_code, "installationId": inst, "operationId": op})),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    let grant = grant_res["grant"].as_str().unwrap().to_string();

    let (st, _, status_auth) = env
        .json_req(
            Method::GET,
            &format!("/v1/admin/connect-codes/{}", inv_id),
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(status_auth["status"], "authorized");

    let cred = "A".repeat(44);
    let grant_hdr = format!("Bearer {}", grant);
    let (st, _, enrolled) = env
        .json_req(
            Method::POST,
            "/v1/device-enrollment/complete",
            &[("authorization", &grant_hdr)],
            Some(json!({
                "installationId": inst,
                "operationId": op,
                "credential": cred,
                "deviceName": "测试手机",
                "sourceId": null,
                "expectedGeneration": null,
            })),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    let dev_id = enrolled["deviceId"].as_str().unwrap().to_string();

    // Cancel after completion returns completed and preserves device
    let (st, _, cancel_res) = env
        .json_req(
            Method::POST,
            &format!("/v1/admin/connect-codes/{}/cancel", inv_id),
            &admin_hdr,
            Some(json!({})),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(cancel_res["status"], "completed");
    assert_eq!(cancel_res["deviceId"], dev_id);

    // Concurrent claim test: issue 2 codes and compete to claim same source
    let mut grants = Vec::new();
    for _ in 0..2 {
        let (_, _, c2) = env
            .json_req(
                Method::POST,
                "/v1/admin/connect-code",
                &admin_hdr,
                Some(json!({})),
            )
            .await;
        let enc2 = c2["connectCode"].as_str().unwrap();
        let p2: Value =
            serde_json::from_slice(&URL_SAFE_NO_PAD.decode(&enc2["MMJOIN2.".len()..]).unwrap())
                .unwrap();
        let i2 = Uuid::new_v4().to_string();
        let o2 = Uuid::new_v4().to_string();
        let (_, _, g2) = env
            .json_req(
                Method::POST,
                "/v1/device-enrollment/authorize",
                &[],
                Some(json!({"code": p2["code"], "installationId": i2, "operationId": o2})),
            )
            .await;
        grants.push((i2, o2, g2["grant"].as_str().unwrap().to_string()));
    }

    let e1 = Arc::clone(&env);
    let e2 = Arc::clone(&env);
    let (i1, o1, g1) = grants[0].clone();
    let (i2, o2, g2) = grants[1].clone();
    let d1 = dev_id.clone();
    let d2 = dev_id.clone();
    let (r1, r2) = tokio::join!(
        async move {
            let h = format!("Bearer {}", g1);
            e1.json_req(
                Method::POST,
                "/v1/device-enrollment/complete",
                &[("authorization", &h)],
                Some(json!({
                    "installationId": i1, "operationId": o1,
                    "credential": "B".repeat(44), "deviceName": "接替1",
                    "sourceId": d1, "expectedGeneration": 1
                })),
            )
            .await
            .0
        },
        async move {
            let h = format!("Bearer {}", g2);
            e2.json_req(
                Method::POST,
                "/v1/device-enrollment/complete",
                &[("authorization", &h)],
                Some(json!({
                    "installationId": i2, "operationId": o2,
                    "credential": "C".repeat(44), "deviceName": "接替2",
                    "sourceId": d2, "expectedGeneration": 1
                })),
            )
            .await
            .0
        }
    );
    let mut codes = vec![r1.as_u16(), r2.as_u16()];
    codes.sort();
    assert_eq!(codes, vec![200, 409]);

    // Repeatable schema apply preserves identity
    apply_schema(&env.pool, &env.schema).await.unwrap();
    let cnt: i64 = sqlx::query("SELECT count(*) AS n FROM devices")
        .fetch_one(&env.pool)
        .await
        .unwrap()
        .try_get("n")
        .unwrap();
    assert_eq!(cnt, 1);

    // Confirm no plaintext secrets in DB
    let row = sqlx::query("SELECT token_hash FROM devices WHERE id=$1")
        .bind(Uuid::parse_str(&dev_id).unwrap())
        .fetch_one(&env.pool)
        .await
        .unwrap();
    let th: String = row.try_get("token_hash").unwrap();
    assert_ne!(th.trim(), cred);
    assert_eq!(hash_token(&cred).len(), 64);

    let env = Arc::try_unwrap(env).ok().expect("unwrap TestEnv");
    env.cleanup().await;
}
