//! 实时增量（Outbox 单真源推送）+ 基准快照 Overlay 热叠加的端到端验证。
//!
//! 覆盖：单真源 Fast-Forward、基准快照老账单墓碑删除、新增/修改账单、账户改名、
//! 资产流水、meta 配置叠加、sync-head 探针、历史快照不受影响、新快照上传后的增量自洁。

mod common;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode};
use chrono::{TimeZone, Utc};
use chrono_tz::Asia::Shanghai;
use serde_json::{json, Value};
use sqlx::Row;
use uuid::Uuid;

use mymoney_home::auth::sha256_bytes;

use common::TestEnv;

fn ts(y: i32, m: u32, d: u32, hh: u32, mm: u32) -> i64 {
    Shanghai
        .with_ymd_and_hms(y, m, d, hh, mm, 0)
        .unwrap()
        .timestamp_millis()
}

fn base_snapshot(exported_at: i64) -> Value {
    json!({
        "kind": "MyMoneyBackup",
        "format": 12,
        "exportedAt": exported_at,
        "bills": [
            {"p": 2000, "ty": "EXPENSE", "t": ts(2026, 10, 2, 12, 0), "me": "旧账单", "sp": "", "st": "manual", "fp": "fp0", "cg": "餐饮", "aid": "acc-a1", "al": false, "ak": "bk-0", "tf": 0, "sid": "s0", "rs": "NONE"},
            {"p": 10000, "ty": "EXPENSE", "t": ts(2026, 10, 5, 12, 0), "me": "午饭", "sp": "", "st": "manual", "fp": "fp1", "cg": "餐饮", "aid": "acc-a1", "al": true, "ak": "bk-1", "tf": 0, "sid": "s1", "rs": "NONE"},
            {"p": 3000, "ty": "EXPENSE", "t": ts(2026, 10, 8, 8, 0), "me": "打车", "sp": "", "st": "manual", "fp": "fp2", "cg": "交通", "aid": "acc-a2", "al": true, "ak": "bk-2", "tf": 0, "sid": "s2", "rs": "NONE"}
        ],
        "assets": {
            "accounts": [
                {"id": "acc-a1", "name": "微信支付", "kind": "PAYMENT", "aliases": [], "sortOrder": 0, "archived": false, "included": true, "initialized": true, "createdAt": ts(2026, 9, 1, 9, 0), "updatedAt": ts(2026, 10, 1, 9, 0)},
                {"id": "acc-a2", "name": "招行信用卡", "kind": "CREDIT", "aliases": [], "sortOrder": 1, "archived": false, "included": true, "initialized": true, "createdAt": ts(2026, 9, 1, 9, 0), "updatedAt": ts(2026, 10, 1, 9, 0)}
            ],
            "entries": [
                {"id": "e1", "operationId": "op1", "accountId": "acc-a1", "deltaCents": 100000, "kind": "OPENING", "note": "期初", "createdAt": ts(2026, 9, 1, 9, 0)},
                {"id": "e2", "operationId": "op2", "accountId": "acc-a2", "deltaCents": -20000, "kind": "OPENING", "note": "期初欠款", "createdAt": ts(2026, 9, 1, 9, 0)}
            ]
        },
        "ledger": {
            "monthStartDay": 1,
            "expense": [{"n": "餐饮", "s": []}, {"n": "交通", "s": []}],
            "income": [{"n": "工资", "s": []}]
        }
    })
}

async fn upload(
    env: &TestEnv,
    auth: &str,
    epoch: &str,
    snap: &Value,
    backup_id: &str,
) -> StatusCode {
    let raw = serde_json::to_vec(snap).unwrap();
    let (status, _, _) = env
        .send(
            Request::builder()
                .method(Method::PUT)
                .uri(format!("/v1/backups/{}", backup_id))
                .header("authorization", auth)
                .header("content-type", "application/json")
                .header("content-length", raw.len().to_string())
                .header("x-content-sha256", sha256_bytes(&raw))
                .header("x-space-epoch", epoch)
                .header("x-app-version", "1.10.3/162")
                .body(Body::from(raw))
                .unwrap(),
        )
        .await;
    status
}

#[allow(clippy::too_many_arguments)]
fn push_item(
    op: &str,
    epoch: Uuid,
    ledger: &str,
    item_id: &str,
    kind: &str,
    base_revision: i64,
    operation: &str,
    content_updated_at: i64,
    payload: Value,
) -> Value {
    json!({
        "operationId": op,
        "epoch": epoch,
        "ledgerId": ledger,
        "itemId": item_id,
        "itemKind": kind,
        "baseRevision": base_revision,
        "operation": operation,
        "schemaVersion": 1,
        "contentUpdatedAt": content_updated_at,
        "payload": payload,
    })
}

#[tokio::test]
async fn realtime_overlay_and_single_source_push() {
    let env = TestEnv::new().await;
    let admin_hdr = [("x-admin-token", env.admin_token.as_str())];

    let (_, _, p) = env
        .json_req(
            Method::POST,
            "/v1/pair",
            &[],
            Some(json!({"code": env.state.pairing.issue(None), "deviceName": "实时同步手机"})),
        )
        .await;
    let auth = format!("Bearer {}", p["token"].as_str().unwrap());
    let epoch_str = p["epoch"].as_str().unwrap().to_string();
    let epoch = Uuid::parse_str(&epoch_str).unwrap();
    let device_id = p["deviceId"].as_str().unwrap().to_string();
    let ledger = device_id.clone(); // 单真源：ledger_id == device_id

    // 先上传一份更早的旧快照（用于验证历史快照不被叠加），再上传最新基准快照。
    let old_id = Uuid::new_v4().to_string();
    let base_id = Uuid::new_v4().to_string();
    assert_eq!(
        upload(
            &env,
            &auth,
            &epoch_str,
            &base_snapshot(ts(2026, 10, 31, 23, 0)),
            &old_id
        )
        .await,
        StatusCode::OK
    );
    assert_eq!(
        upload(
            &env,
            &auth,
            &epoch_str,
            &base_snapshot(ts(2026, 11, 1, 3, 0)),
            &base_id
        )
        .await,
        StatusCode::OK
    );

    // 基准快照（叠加前）总额 15000 分。
    let (_, _, bills0) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/bills?pageSize=100&tz=Asia/Shanghai",
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(bills0["summary"]["expenseCents"], 15000);
    assert_eq!(bills0["total"], 3);

    let t1 = ts(2026, 11, 1, 4, 0);
    let mut items = vec![
        // 1) 删除只存在于基准快照里的老账单（ledger_items 中尚无该行）→ 墓碑插入
        push_item(
            "op_0000000000000010",
            epoch,
            &ledger,
            "bill:s0",
            "bill",
            0,
            "delete",
            t1,
            json!({"syncId": "s0"}),
        ),
        // 2) 新增账单（同时带资产扣减流水）
        push_item(
            "op_0000000000000011",
            epoch,
            &ledger,
            "bill:s3",
            "bill",
            0,
            "put",
            t1,
            json!({
                "syncId": "s3", "priceCents": 5000, "type": "EXPENSE", "timeMillis": ts(2026, 11, 1, 13, 0),
                "merchant": "咖啡", "sourcePkg": "", "strategy": "手动记账", "fingerprint": "fp3",
                "createdAt": ts(2026, 11, 1, 13, 0), "category": "餐饮", "subCategory": "咖啡茶饮",
                "account": "微信支付", "accountId": "acc-a1", "assetLinked": true, "assetKey": "bk-3",
                "transferFeeCents": 0, "updatedAt": t1, "deletedAt": null, "reimburseStatus": "NONE"
            }),
        ),
        // 3) 账户改名：账单的 resolved_account 也要跟着变
        push_item(
            "op_0000000000000012",
            epoch,
            &ledger,
            "account:acc-a1",
            "account",
            999, // 故意给错 baseRevision：单真源 Fast-Forward 应直接采纳
            "put",
            t1,
            json!({
                "syncId": "acc-a1", "name": "微信零钱", "kind": "PAYMENT", "aliases": "[]",
                "sortOrder": 0, "archived": false, "included": true, "initialized": true,
                "createdAt": ts(2026, 9, 1, 9, 0), "updatedAt": t1
            }),
        ),
        // 4) 资产流水：账户余额下降 5000 分
        push_item(
            "op_0000000000000013",
            epoch,
            &ledger,
            "entry:e3",
            "entry",
            0,
            "put",
            t1,
            json!({
                "syncId": "e3", "operationId": "op3", "accountId": "acc-a1", "deltaCents": -5000,
                "kind": "BILL", "note": "咖啡", "createdAt": ts(2026, 11, 1, 13, 0),
                "billKey": "bk-3", "updatedAt": t1, "deletedAt": null
            }),
        ),
        // 5) meta:config：记账周期与分类树
        push_item(
            "op_0000000000000014",
            epoch,
            &ledger,
            "meta:config",
            "meta",
            0,
            "put",
            t1,
            json!({
                "syncId": "config", "monthStartDay": 12,
                "expenseCategories": [{"name": "餐饮", "subs": ["午餐", "咖啡茶饮"]}],
                "incomeCategories": [{"name": "工资", "subs": []}],
                "updatedAt": t1
            }),
        ),
        // 6) 陈旧 baseRevision 下修改既有账单：同设备应 Fast-Forward 而不是冲突
        push_item(
            "op_0000000000000015",
            epoch,
            &ledger,
            "bill:s1",
            "bill",
            0,
            "put",
            t1,
            json!({
                "syncId": "s1", "priceCents": 11000, "type": "EXPENSE", "timeMillis": ts(2026, 10, 5, 12, 0),
                "merchant": "午饭", "sourcePkg": "", "strategy": "manual", "fingerprint": "fp1",
                "createdAt": ts(2026, 10, 5, 12, 0), "category": "餐饮", "account": "微信支付",
                "accountId": "acc-a1", "assetLinked": true, "assetKey": "bk-1", "transferFeeCents": 0,
                "updatedAt": t1, "deletedAt": null, "reimburseStatus": "PENDING"
            }),
        ),
    ];
    // 同一笔账单第二次修改，baseRevision 仍然陈旧（模拟离线/重装后的 Outbox）
    items.push(push_item(
        "op_0000000000000016",
        epoch,
        &ledger,
        "bill:s1",
        "bill",
        0,
        "put",
        t1 + 1,
        json!({
            "syncId": "s1", "priceCents": 12000, "type": "EXPENSE", "timeMillis": ts(2026, 10, 5, 12, 0),
            "merchant": "午饭", "sourcePkg": "", "strategy": "manual", "fingerprint": "fp1",
            "createdAt": ts(2026, 10, 5, 12, 0), "category": "餐饮", "account": "微信支付",
            "accountId": "acc-a1", "assetLinked": true, "assetKey": "bk-1", "transferFeeCents": 0,
            "updatedAt": t1 + 1, "deletedAt": null, "reimburseStatus": "PENDING"
        }),
    ));

    let (status, _, pushed) = env
        .json_req(
            Method::POST,
            "/v1/ledger/push",
            &[("authorization", &auth)],
            Some(json!({
                "protocolVersion": 1,
                "spaceId": p["spaceId"],
                "ledgerId": ledger,
                "epoch": epoch,
                "items": items,
            })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    for row in pushed["items"].as_array().unwrap() {
        assert_eq!(row["status"], "ok", "push result: {}", row);
    }

    // 墓碑删除生效：老账单 s0 不再出现，新增 s3 出现，s1 金额更新为 12000。
    let (_, _, bills) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/bills?pageSize=100&tz=Asia/Shanghai",
            &admin_hdr,
            None,
        )
        .await;
    let rows = bills["items"].as_array().unwrap();
    let sync_ids: Vec<&str> = rows.iter().filter_map(|r| r["syncId"].as_str()).collect();
    assert!(sync_ids.contains(&"s1"));
    assert!(sync_ids.contains(&"s2"));
    assert!(sync_ids.contains(&"s3"));
    assert!(!sync_ids.contains(&"s0"));
    // 15000 - 2000(删除) + 5000(新增) + 2000(s1 从 10000 改到 12000) = 20000
    assert_eq!(bills["summary"]["expenseCents"], 20000);
    assert_eq!(bills["total"], 3);
    let s3 = rows.iter().find(|r| r["syncId"] == "s3").unwrap();
    assert_eq!(s3["category"], "餐饮");
    assert_eq!(s3["account"], "微信零钱");
    let s1 = rows.iter().find(|r| r["syncId"] == "s1").unwrap();
    assert_eq!(s1["priceCents"], 12000);
    assert_eq!(s1["reimburseStatus"], "PENDING");
    assert_eq!(s1["account"], "微信零钱");

    // 账户改名 + 流水叠加：余额 100000 - 5000 = 95000 分。
    let (_, _, assets) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/assets?tz=Asia/Shanghai",
            &admin_hdr,
            None,
        )
        .await;
    let accounts = assets["accounts"].as_array().unwrap();
    let a1 = accounts.iter().find(|a| a["id"] == "acc-a1").unwrap();
    assert_eq!(a1["name"], "微信零钱");
    assert_eq!(a1["balanceCents"], 95000);
    assert_eq!(assets["summary"]["netCents"], 75000); // 95000 - 20000 欠款

    // sync-head 探针：叠加 7 条增量，账单 3 笔，记账周期 12 日。
    let (status, _, head) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/sync-head",
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(head["backupId"], base_id);
    assert_eq!(head["overlayRevision"], 7);
    assert_eq!(head["billCount"], 3);
    assert_eq!(head["monthStartDay"], 12);
    assert!(head["overlayUpdatedAt"].is_string());

    // 历史旧快照必须保持原始静态数据，不叠加任何增量。
    let (_, _, old_bills) = env
        .json_req(
            Method::GET,
            &format!(
                "/v1/admin/dashboard/bills?pageSize=100&backupId={}&tz=Asia/Shanghai",
                old_id
            ),
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(old_bills["snapshot"]["overlayRevision"], 0);
    assert_eq!(old_bills["snapshot"]["monthStartDay"], 1);
    assert_eq!(old_bills["total"], 3);
    assert_eq!(old_bills["summary"]["expenseCents"], 15000);

    // 再上传一份全新的全量快照：已覆盖的增量应被自动清理，恢复纯静态读取。
    let newer_id = Uuid::new_v4().to_string();
    assert_eq!(
        upload(
            &env,
            &auth,
            &epoch_str,
            &base_snapshot(Utc::now().timestamp_millis() + 86_400_000),
            &newer_id
        )
        .await,
        StatusCode::OK
    );
    let (_, _, head2) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/sync-head",
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(head2["backupId"], newer_id);
    assert_eq!(head2["overlayRevision"], 0);
    assert_eq!(head2["billCount"], 3);
    let remaining = sqlx::query(
        "SELECT id, item_kind, content_updated_at, deleted_at FROM ledger_items WHERE space_id=$1 AND ledger_id=$2",
    )
    .bind(Uuid::parse_str(p["spaceId"].as_str().unwrap()).unwrap())
    .bind(Uuid::parse_str(&ledger).unwrap())
    .fetch_all(&env.pool)
    .await
    .unwrap();
    for row in &remaining {
        eprintln!(
            "remaining: id={} kind={} updated={:?} deleted={:?}",
            row.try_get::<String, _>("id").unwrap(),
            row.try_get::<String, _>("item_kind").unwrap(),
            row.try_get::<Option<i64>, _>("content_updated_at").unwrap(),
            row.try_get::<Option<chrono::DateTime<Utc>>, _>("deleted_at")
                .unwrap()
        );
    }
    assert_eq!(remaining.len(), 0, "新快照上传后应清理已覆盖的增量");
}

#[tokio::test]
async fn sync_head_requires_admin_token() {
    let env = TestEnv::new().await;
    let (status, _, _) = env
        .json_req(Method::GET, "/v1/admin/dashboard/sync-head", &[], None)
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
}

/// 用本机真实的 2,300+ 笔快照做一次 Overlay 冒烟：验证驼峰增量 payload 能叠加到
/// 真实备份短键快照上，且缓存/探针数字自洽。机器上没有真实备份时自动跳过。
#[tokio::test]
async fn realistic_large_snapshot_overlay_smoke() {
    use std::fs;
    use std::path::{Path, PathBuf};

    let root = Path::new("/Users/caozl/mymoney-home/backups");
    if !root.is_dir() {
        return;
    }
    let mut files: Vec<PathBuf> = Vec::new();
    if let Ok(spaces) = fs::read_dir(root) {
        for space in spaces.flatten() {
            if let Ok(entries) = fs::read_dir(space.path()) {
                files.extend(
                    entries
                        .flatten()
                        .map(|e| e.path())
                        .filter(|p| p.extension().map(|x| x == "json").unwrap_or(false)),
                );
            }
        }
    }
    files.sort_by_key(|p| fs::metadata(p).and_then(|m| m.modified()).ok());
    let Some(path) = files.pop() else { return };
    let raw = fs::read(&path).expect("read real backup");
    let snap: Value = serde_json::from_slice(&raw).expect("parse real backup");
    let Some((sid, price, time)) = snap["bills"].as_array().and_then(|rows| {
        rows.iter().find_map(|b| {
            if b["ty"] == "EXPENSE" && !b["sid"].as_str().unwrap_or("").is_empty() {
                Some((
                    b["sid"].as_str().unwrap().to_string(),
                    b["p"].as_i64().unwrap_or(0),
                    b["t"].as_i64().unwrap_or(0),
                ))
            } else {
                None
            }
        })
    }) else {
        return;
    };

    let env = TestEnv::new().await;
    let admin_hdr = [("x-admin-token", env.admin_token.as_str())];
    let (_, _, p) = env
        .json_req(
            Method::POST,
            "/v1/pair",
            &[],
            Some(json!({"code": env.state.pairing.issue(None), "deviceName": "真实快照"})),
        )
        .await;
    let auth = format!("Bearer {}", p["token"].as_str().unwrap());
    let epoch_str = p["epoch"].as_str().unwrap().to_string();
    let epoch = Uuid::parse_str(&epoch_str).unwrap();
    let ledger = p["deviceId"].as_str().unwrap().to_string();
    let backup_id = Uuid::new_v4().to_string();
    assert_eq!(
        upload(&env, &auth, &epoch_str, &snap, &backup_id).await,
        StatusCode::OK
    );

    let (_, _, before) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/bills?pageSize=1",
            &admin_hdr,
            None,
        )
        .await;
    let base_count = before["total"].as_i64().unwrap();
    let base_expense = before["summary"]["expenseCents"].as_i64().unwrap();

    let t = ts(2026, 11, 19, 23, 0);
    let pushed = push_item(
        "op_0000000000000020",
        epoch,
        &ledger,
        &format!("bill:{}", sid),
        "bill",
        0,
        "put",
        t,
        json!({
            "syncId": sid, "priceCents": price + 100, "type": "EXPENSE",
            "timeMillis": time, "category": "餐饮", "reimburseStatus": "NONE",
            "updatedAt": t, "deletedAt": null
        }),
    );
    let (status, _, res) = env
        .json_req(
            Method::POST,
            "/v1/ledger/push",
            &[("authorization", &auth)],
            Some(json!({
                "protocolVersion": 1, "spaceId": p["spaceId"], "ledgerId": ledger,
                "epoch": epoch, "items": [pushed]
            })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(res["items"][0]["status"], "ok");

    let (_, _, after) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/bills?pageSize=1",
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(after["total"], base_count, "更新既有账单不应改变笔数");
    assert_eq!(after["summary"]["expenseCents"], base_expense + 100);
    assert_eq!(after["snapshot"]["overlayRevision"], 1);
    assert_eq!(after["snapshot"]["billCount"], base_count);
}

/// 水位线：早于基准快照的非删除增量已经包含在快照里，不重复覆盖；墓碑不受水位线限制。
#[tokio::test]
async fn overlay_skips_stale_rows_but_applies_tombstones() {
    let env = TestEnv::new().await;
    let admin_hdr = [("x-admin-token", env.admin_token.as_str())];
    let (_, _, p) = env
        .json_req(
            Method::POST,
            "/v1/pair",
            &[],
            Some(json!({"code": env.state.pairing.issue(None), "deviceName": "水位线手机"})),
        )
        .await;
    let auth = format!("Bearer {}", p["token"].as_str().unwrap());
    let epoch_str = p["epoch"].as_str().unwrap().to_string();
    let epoch = Uuid::parse_str(&epoch_str).unwrap();
    let ledger = p["deviceId"].as_str().unwrap().to_string();

    let backup_id = Uuid::new_v4().to_string();
    assert_eq!(
        upload(
            &env,
            &auth,
            &epoch_str,
            &base_snapshot(ts(2026, 11, 2, 3, 0)),
            &backup_id
        )
        .await,
        StatusCode::OK
    );

    // 1) 早于快照导出的 put：必须被忽略（快照已包含更新的状态）。
    let stale = push_item(
        "op_0000000000000030",
        epoch,
        &ledger,
        "bill:s1",
        "bill",
        0,
        "put",
        ts(2026, 11, 1, 0, 0),
        json!({
            "syncId": "s1", "priceCents": 99999, "type": "EXPENSE",
            "timeMillis": ts(2026, 10, 5, 12, 0), "category": "餐饮",
            "reimburseStatus": "NONE", "updatedAt": ts(2026, 11, 1, 0, 0), "deletedAt": null
        }),
    );
    let (status, _, res) = env
        .json_req(
            Method::POST,
            "/v1/ledger/push",
            &[("authorization", &auth)],
            Some(json!({
                "protocolVersion": 1, "spaceId": p["spaceId"], "ledgerId": ledger,
                "epoch": epoch, "items": [stale]
            })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(res["items"][0]["status"], "ok");

    let (_, _, head) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/sync-head",
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(head["overlayRevision"], 0, "过期 put 不应产生 overlay");
    let (_, _, bills) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/bills?pageSize=100&tz=Asia/Shanghai",
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(bills["summary"]["expenseCents"], 15000);
    assert_eq!(bills["total"], 3);

    // 2) 墓碑（删除发生在快照之后）必须生效，即使 contentUpdatedAt 是旧的。
    let tomb = push_item(
        "op_0000000000000031",
        epoch,
        &ledger,
        "bill:s2",
        "bill",
        0,
        "delete",
        ts(2026, 10, 1, 0, 0),
        json!({"syncId": "s2"}),
    );
    let (status, _, res) = env
        .json_req(
            Method::POST,
            "/v1/ledger/push",
            &[("authorization", &auth)],
            Some(json!({
                "protocolVersion": 1, "spaceId": p["spaceId"], "ledgerId": ledger,
                "epoch": epoch, "items": [tomb]
            })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(res["items"][0]["status"], "ok");

    let (_, _, head) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/sync-head",
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(head["overlayRevision"], 2);
    assert_eq!(head["billCount"], 2);
    let (_, _, bills) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/bills?pageSize=100&tz=Asia/Shanghai",
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(bills["total"], 2);
    assert_eq!(bills["summary"]["expenseCents"], 12000);
}
