mod common;

use std::fs;
use std::path::Path;
use std::time::Instant;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode};
use chrono::{TimeZone, Utc};
use chrono_tz::Asia::Shanghai;
use serde_json::{json, Value};
use sqlx::Row;
use uuid::Uuid;

use mymoney_home::analytics::queries::{
    build_analytics, build_assets, build_audit, build_overview, query_bills, BillFilter,
};
use mymoney_home::analytics::snapshot::{parse_snapshot_from_file, SnapshotMeta};
use mymoney_home::auth::sha256_bytes;

use common::TestEnv;

fn ts_shanghai(y: i32, m: u32, d: u32, hh: u32, mm: u32) -> i64 {
    Shanghai
        .with_ymd_and_hms(y, m, d, hh, mm, 0)
        .unwrap()
        .timestamp_millis()
}

fn build_known_totals_snapshot() -> Value {
    let acc_pay = "acc-pay-1";
    let acc_credit = "acc-credit-2";
    let acc_uninit = "acc-uninit-3";
    let acc_excluded = "acc-excl-4";

    let accounts = json!([
        {"id": acc_pay, "name": "微信支付", "kind": "PAYMENT", "aliases": [], "sortOrder": 0, "archived": false, "included": true, "initialized": true, "createdAt": ts_shanghai(2026, 9, 1, 9, 0), "updatedAt": ts_shanghai(2026, 11, 10, 9, 0)},
        {"id": acc_credit, "name": "招行信用卡", "kind": "CREDIT", "aliases": [], "sortOrder": 1, "archived": false, "included": true, "initialized": true, "createdAt": ts_shanghai(2026, 9, 1, 9, 0), "updatedAt": ts_shanghai(2026, 11, 10, 9, 0)},
        {"id": acc_uninit, "name": "待初始化现金", "kind": "CASH", "aliases": [], "sortOrder": 2, "archived": false, "included": true, "initialized": false, "createdAt": ts_shanghai(2026, 9, 1, 9, 0), "updatedAt": ts_shanghai(2026, 9, 1, 9, 0)},
        {"id": acc_excluded, "name": "公积金", "kind": "OTHER_ASSET", "aliases": [], "sortOrder": 3, "archived": false, "included": false, "initialized": true, "createdAt": ts_shanghai(2026, 9, 1, 9, 0), "updatedAt": ts_shanghai(2026, 9, 1, 9, 0)},
    ]);

    // 账户余额核对（已知手算真值）：
    // acc_pay: 期初 +100000 (+1000元) - 12000 (餐饮) - 8000 (待报销交通) - 5000 (已报销餐饮) + 5000 (报销退回) + 300000 (工资) - 20000 (转出) - 200 (手续费) + 1500 (退款) = 361300 分
    // acc_credit: 期初 0 - 10000 (购物) - 10000 (冲销原记录先减后加) -> 我们设：期初 -20000, 转入 +20000, 未分类消费 -3000 => -3000 分 (负债 3000 分)
    // acc_excluded: 期初 +500000 (不计入总资产)
    // 总资产 = 361300, 总负债 = 3000, 净资产 = 358300, unknownCount = 1
    let entries = json!([
        {"id": "e1", "operationId": "op1", "accountId": acc_pay, "deltaCents": 100000, "kind": "OPENING", "note": "期初", "createdAt": ts_shanghai(2026, 9, 1, 9, 0), "billKey": null, "reversalOf": null},
        {"id": "e2", "operationId": "op2", "accountId": acc_credit, "deltaCents": -20000, "kind": "OPENING", "note": "期初欠款", "createdAt": ts_shanghai(2026, 9, 1, 9, 0), "billKey": null, "reversalOf": null},
        {"id": "e3", "operationId": "op3", "accountId": acc_excluded, "deltaCents": 500000, "kind": "OPENING", "note": "公积金", "createdAt": ts_shanghai(2026, 9, 1, 9, 0), "billKey": null, "reversalOf": null},
        {"id": "e4", "operationId": "op4", "accountId": acc_pay, "deltaCents": -12000, "kind": "BILL", "note": "火锅", "createdAt": ts_shanghai(2026, 10, 15, 19, 0), "billKey": "bk-1", "reversalOf": null},
        {"id": "e5", "operationId": "op5", "accountId": acc_pay, "deltaCents": -8000, "kind": "BILL", "note": "打车", "createdAt": ts_shanghai(2026, 10, 20, 8, 30), "billKey": "bk-2", "reversalOf": null},
        {"id": "e6", "operationId": "op6", "accountId": acc_pay, "deltaCents": -5000, "kind": "BILL", "note": "差旅餐", "createdAt": ts_shanghai(2026, 10, 22, 12, 0), "billKey": "bk-3", "reversalOf": null},
        {"id": "e7", "operationId": "op7", "accountId": acc_pay, "deltaCents": 5000, "kind": "REIMBURSE", "note": "报销到账", "createdAt": ts_shanghai(2026, 10, 25, 15, 0), "billKey": "bk-3", "reversalOf": null},
        {"id": "e8", "operationId": "op8", "accountId": acc_pay, "deltaCents": 300000, "kind": "BILL", "note": "工资", "createdAt": ts_shanghai(2026, 10, 15, 10, 0), "billKey": "bk-4", "reversalOf": null},
        {"id": "e9", "operationId": "op9", "accountId": acc_pay, "deltaCents": -20000, "kind": "TRANSFER", "note": "还款转出", "createdAt": ts_shanghai(2026, 11, 1, 10, 0), "billKey": "bk-5", "reversalOf": null},
        {"id": "e10", "operationId": "op10", "accountId": acc_credit, "deltaCents": 20000, "kind": "TRANSFER", "note": "还款转入", "createdAt": ts_shanghai(2026, 11, 1, 10, 0), "billKey": "bk-5", "reversalOf": null},
        {"id": "e11", "operationId": "op11", "accountId": acc_pay, "deltaCents": -200, "kind": "BILL", "note": "手续费", "createdAt": ts_shanghai(2026, 11, 1, 10, 0), "billKey": "bk-fee", "reversalOf": null},
        {"id": "e12", "operationId": "op12", "accountId": acc_pay, "deltaCents": 1500, "kind": "BILL", "note": "退款", "createdAt": ts_shanghai(2026, 11, 5, 14, 0), "billKey": "bk-6", "reversalOf": null},
        {"id": "e13", "operationId": "op13", "accountId": acc_credit, "deltaCents": -3000, "kind": "BILL", "note": "未分类", "createdAt": ts_shanghai(2026, 11, 8, 21, 0), "billKey": "bk-7", "reversalOf": null},
        // 冲销测试：-4000 再 +4000 净变动为 0
        {"id": "e14", "operationId": "op14", "accountId": acc_credit, "deltaCents": -4000, "kind": "BILL", "note": "误记", "createdAt": ts_shanghai(2026, 11, 9, 10, 0), "billKey": "bk-rev", "reversalOf": null},
        {"id": "e15", "operationId": "op15", "accountId": acc_credit, "deltaCents": 4000, "kind": "REVERSAL", "note": "冲销误记", "createdAt": ts_shanghai(2026, 11, 9, 10, 5), "billKey": "bk-rev", "reversalOf": "e14"},
    ]);

    // 周期 2026-10-12 ~ 2026-11-11（monthStartDay=12）：
    // 净支出 = 12000(餐饮) + 8000(待报销交通) + 200(手续费) + 3000(未分类) = 23200 分（已报销 5000 扣除；转账本金 20000 不计）
    // 收入 = 300000 分
    // 退款 = 1500 分
    // 净结余 = 300000 - 23200 + 1500 = 278300 分
    // 再放一笔上期（2026-09-12 ~ 2026-10-11）支出：6000 分（仅1笔，验证小基数 smallBase=true）
    let bills = json!([
        {"p": 6000, "ty": "EXPENSE", "t": ts_shanghai(2026, 10, 1, 12, 0), "me": "便利店", "sp": "", "st": "manual", "fp": "fp0", "cg": "餐饮", "sc": "午餐", "aid": acc_pay, "al": false, "ak": "bk-0", "tf": 0, "sid": "s0", "rs": "NONE"},
        {"p": 12000, "ty": "EXPENSE", "t": ts_shanghai(2026, 10, 15, 19, 0), "me": "=SUM(A1:A2)餐饮", "sp": "com.tencent.mm", "st": "spec:rule_1", "fp": "fp1", "cg": "餐饮", "sc": "晚餐", "no": "火锅聚餐", "aid": acc_pay, "al": true, "ak": "bk-1", "tf": 0, "sid": "s1", "rs": "NONE"},
        {"p": 8000, "ty": "EXPENSE", "t": ts_shanghai(2026, 10, 20, 8, 30), "me": "滴滴出行", "sp": "com.eg.android.AlipayGphone", "st": "notification:alipay", "fp": "fp2", "cg": "交通", "sc": "打车", "no": "机场打车", "aid": acc_pay, "al": true, "ak": "bk-2", "tf": 0, "sid": "s2", "rs": "PENDING"},
        {"p": 5000, "ty": "EXPENSE", "t": ts_shanghai(2026, 10, 22, 12, 0), "me": "差旅餐厅", "sp": "", "st": "manual", "fp": "fp3", "cg": "餐饮", "sc": "午餐", "no": "报销餐", "aid": acc_pay, "al": true, "ak": "bk-3", "tf": 0, "sid": "s3", "rs": "REIMBURSED", "rat": ts_shanghai(2026, 10, 25, 15, 0), "raid": acc_pay},
        {"p": 300000, "ty": "INCOME", "t": ts_shanghai(2026, 10, 15, 10, 0), "me": "公司", "sp": "", "st": "manual", "fp": "fp4", "cg": "工资", "aid": acc_pay, "al": true, "ak": "bk-4", "tf": 0, "sid": "s4", "rs": "NONE"},
        {"p": 20000, "ty": "TRANSFER", "t": ts_shanghai(2026, 11, 1, 10, 0), "sp": "", "st": "manual", "fp": "fp5", "aid": acc_pay, "taid": acc_credit, "al": true, "ak": "bk-5", "tf": 200, "sid": "s5", "rs": "NONE"},
        {"p": 200, "ty": "EXPENSE", "t": ts_shanghai(2026, 11, 1, 10, 0), "sp": "", "st": "manual", "fp": "fp-fee", "cg": "居住", "sc": "手续费", "aid": acc_pay, "al": true, "ak": "bk-fee", "tf": 0, "fpk": "bk-5", "sid": "s-fee", "rs": "NONE"},
        {"p": 1500, "ty": "REFUND", "t": ts_shanghai(2026, 11, 5, 14, 0), "me": "电商退款", "sp": "com.eg.android.AlipayGphone", "st": "manual", "fp": "fp6", "cg": "购物", "aid": acc_pay, "al": true, "ak": "bk-6", "tf": 0, "sid": "s6", "rs": "NONE"},
        {"p": 3000, "ty": "EXPENSE", "t": ts_shanghai(2026, 11, 8, 21, 0), "me": "小店", "sp": "", "st": "manual", "fp": "fp7", "no": "夜宵", "aid": acc_credit, "al": true, "ak": "bk-7", "tf": 0, "sid": "s7", "rs": "NONE"}
    ]);

    let deleted_bill = json!({
        "id": 999, "priceCents": 9900, "type": "EXPENSE", "timeMillis": ts_shanghai(2026, 10, 18, 12, 0),
        "sourcePkg": "", "strategy": "manual", "fingerprint": "del-fp", "createdAt": ts_shanghai(2026, 10, 18, 12, 0),
        "assetKey": "del-ak", "assetLinked": false, "transferFeeCents": 0, "account": "微信支付", "note": "已删测试"
    });
    let deletions = json!([{
        "eventId": Uuid::new_v4().to_string(),
        "deletedAt": ts_shanghai(2026, 10, 19, 10, 0),
        "origin": "bill_detail",
        "principalId": 999,
        "recognitionId": null,
        "groupId": "del-ak",
        "snapshotVersion": 1,
        "snapshotJson": serde_json::to_string(&json!({"bills": [deleted_bill]})).unwrap()
    }]);

    json!({
        "kind": "MyMoneyBackup",
        "format": 12,
        "exportedAt": ts_shanghai(2026, 11, 10, 20, 0),
        "bills": bills,
        "deletions": deletions,
        "assets": {"accounts": accounts, "entries": entries},
        "ledger": {
            "expense": [{"n": "餐饮", "s": ["午餐", "晚餐"]}, {"n": "交通", "s": ["打车"]}, {"n": "居住", "s": ["手续费"]}],
            "income": [{"n": "工资", "s": []}],
            "accounts": ["微信支付", "招行信用卡"],
            "noteTemplate": "{商户}",
            "monthStartDay": 12
        }
    })
}

#[tokio::test]
async fn dashboard_known_totals_assets_export_diff_and_security() {
    let env = TestEnv::new().await;
    let admin_hdr = [("x-admin-token", env.admin_token.as_str())];

    // 未解锁调用仪表盘接口必须返回 403
    let (st, _, _) = env
        .json_req(Method::GET, "/v1/admin/dashboard/overview", &[], None)
        .await;
    assert_eq!(st, StatusCode::FORBIDDEN);

    // 注册一台设备并上传两份快照（第一份少1笔，第二份为完整 Known Totals 快照）
    let (_, _, p) = env
        .json_req(
            Method::POST,
            "/v1/pair",
            &[],
            Some(json!({"code": env.state.pairing.issue(None), "deviceName": "财务核对手机"})),
        )
        .await;
    let auth = format!("Bearer {}", p["token"].as_str().unwrap());
    let epoch = p["epoch"].as_str().unwrap().to_string();
    let dev_id = Uuid::parse_str(p["deviceId"].as_str().unwrap()).unwrap();

    // 确保 device.last_seen_at 初始为 NULL，后续管理员读取绝不能改动它
    let before_seen: Option<chrono::DateTime<Utc>> =
        sqlx::query("SELECT last_seen_at FROM devices WHERE id=$1")
            .bind(dev_id)
            .fetch_one(&env.pool)
            .await
            .unwrap()
            .try_get("last_seen_at")
            .unwrap();
    assert!(before_seen.is_none());

    let snap1 = json!({
        "kind": "MyMoneyBackup", "format": 12, "exportedAt": ts_shanghai(2026, 10, 18, 20, 0),
        "bills": [{"p": 12000, "ty": "EXPENSE", "t": ts_shanghai(2026, 10, 15, 19, 0), "sp": "", "st": "manual", "fp": "fp1", "cg": "餐饮", "sid": "s1", "rs": "NONE"}],
        "assets": {"accounts": [{"id": "acc-pay-1", "name": "微信支付", "kind": "PAYMENT", "aliases": [], "sortOrder": 0, "archived": false, "included": true, "initialized": true, "createdAt": ts_shanghai(2026, 9, 1, 9, 0), "updatedAt": ts_shanghai(2026, 10, 15, 19, 0)}],
                   "entries": [{"id": "e1", "operationId": "op1", "accountId": "acc-pay-1", "deltaCents": 100000, "kind": "OPENING", "note": "期初", "createdAt": ts_shanghai(2026, 9, 1, 9, 0)}]},
        "ledger": {"monthStartDay": 12}
    });
    let raw1 = serde_json::to_vec(&snap1).unwrap();
    let bid1 = Uuid::new_v4().to_string();
    let (st, _, _) = env
        .send(
            Request::builder()
                .method(Method::PUT)
                .uri(format!("/v1/backups/{}", bid1))
                .header("authorization", &auth)
                .header("content-type", "application/json")
                .header("content-length", raw1.len().to_string())
                .header("x-content-sha256", sha256_bytes(&raw1))
                .header("x-space-epoch", &epoch)
                .header("x-app-version", "1.10.3/162")
                .body(Body::from(raw1))
                .unwrap(),
        )
        .await;
    assert_eq!(st, StatusCode::OK);

    let snap2 = build_known_totals_snapshot();
    let raw2 = serde_json::to_vec(&snap2).unwrap();
    let bid2 = Uuid::new_v4().to_string();
    let (st, _, _) = env
        .send(
            Request::builder()
                .method(Method::PUT)
                .uri(format!("/v1/backups/{}", bid2))
                .header("authorization", &auth)
                .header("content-type", "application/json")
                .header("content-length", raw2.len().to_string())
                .header("x-content-sha256", sha256_bytes(&raw2))
                .header("x-space-epoch", &epoch)
                .header("x-app-version", "1.10.3/162")
                .body(Body::from(raw2.clone()))
                .unwrap(),
        )
        .await;
    assert_eq!(st, StatusCode::OK);

    // 1. 快照列表默认选中最新 bid2
    let (st, _, snaps) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/snapshots",
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(snaps["defaultBackupId"], bid2);
    // 默认快照的记账周期起始日随列表下发，避免 Web 顶栏回落到「记账周期(1日)」
    let snap_items = snaps["items"].as_array().expect("snapshots.items 应为数组");
    assert_eq!(snap_items[0]["backupId"], bid2);
    assert_eq!(snap_items[0]["monthStartDay"], 12);

    // 2. 总览 Known Totals（查 2026-10-12 ~ 2026-11-11 周期）
    let (st, _, ov) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/overview?periodMode=ledger&start=2026-10-12&end=2026-11-11",
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(ov["totals"]["expenseCents"], 23200);
    assert_eq!(ov["totals"]["incomeCents"], 300000);
    assert_eq!(ov["totals"]["refundCents"], 1500);
    assert_eq!(ov["totals"]["balanceCents"], 278300);
    assert_eq!(ov["totals"]["transferCents"], 20000);
    assert_eq!(ov["kpis"]["netWorth"]["assetsCents"], 361300);
    assert_eq!(ov["kpis"]["netWorth"]["debtsCents"], 3000);
    assert_eq!(ov["kpis"]["netWorth"]["netCents"], 358300);
    assert_eq!(ov["kpis"]["netWorth"]["unknownCount"], 1);
    // 上期仅 1 笔 -> smallBase = true, percent = null
    assert_eq!(ov["kpis"]["netExpense"]["delta"]["smallBase"], true);
    assert!(ov["kpis"]["netExpense"]["delta"]["percent"].is_null());
    // 已删测试账单绝不计入正常账单，但待报销=8000，未分类=1
    assert_eq!(ov["signals"]["pendingReimburseCents"], 8000);
    assert_eq!(ov["signals"]["pendingReimburseCount"], 1);
    assert_eq!(ov["signals"]["periodUncategorizedCount"], 1);
    assert_eq!(ov["topCategories"][0]["name"], "餐饮");
    assert_eq!(ov["topCategories"][0]["cents"], 12000);

    // 3. 收支深度分析：验证餐饮下钻及时段矩阵
    let (st, _, an) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/analytics?periodMode=ledger&start=2026-10-12&end=2026-11-11&category=%E9%A4%90%E9%A5%AE",
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(an["activeCategory"], "餐饮");
    assert_eq!(an["subCategories"][0]["name"], "晚餐");
    assert_eq!(an["subCategories"][0]["cents"], 12000);

    // 4. 明细筛选 + CSV UTF-8 BOM & 公式注入防护导出
    let (st, _, bills_res) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/bills?start=2026-10-12&end=2026-11-11&type=EXPENSE",
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(bills_res["summary"]["expenseCents"], 23200);
    // 5 条支出（12000, 8000, 5000已报销, 200手续费, 3000未分类）
    assert_eq!(bills_res["total"], 5);

    let (st, hdrs, csv_bytes) = env
        .send(
            Request::builder()
                .method(Method::GET)
                .uri("/v1/admin/dashboard/bills/export?start=2026-10-12&end=2026-11-11&format=csv")
                .header("x-admin-token", env.admin_token.as_str())
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert!(hdrs
        .get("content-type")
        .unwrap()
        .to_str()
        .unwrap()
        .starts_with("text/csv"));
    assert_eq!(&csv_bytes[..3], &[0xEF, 0xBB, 0xBF]);
    let csv_text = String::from_utf8(csv_bytes[3..].to_vec()).unwrap();
    assert!(csv_text.contains("'=SUM(A1:A2)餐饮"));

    // 5. 资产历史走势终点必须等于当前净资产 358300
    let (st, _, assets_res) = env
        .json_req(Method::GET, "/v1/admin/dashboard/assets", &admin_hdr, None)
        .await;
    assert_eq!(st, StatusCode::OK);
    let timeline = assets_res["timeline"].as_array().unwrap();
    let last_pt = timeline.last().unwrap();
    assert_eq!(last_pt["netCents"], assets_res["summary"]["netCents"]);
    assert_eq!(last_pt["netCents"], 358300);

    // 6. 审计页：待报销、已报销、未分类与删除日志
    let (st, _, audit_res) = env
        .json_req(Method::GET, "/v1/admin/dashboard/audit", &admin_hdr, None)
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(audit_res["reimburse"]["pendingCents"], 8000);
    assert_eq!(audit_res["reimburse"]["reimbursedCents"], 5000);
    assert_eq!(audit_res["uncategorized"]["count"], 1);
    assert_eq!(audit_res["deletions"].as_array().unwrap().len(), 1);
    assert_eq!(audit_res["deletions"][0]["principalId"], 999);

    // 7. 两份快照 Diff
    let (st, _, diff_res) = env
        .json_req(
            Method::GET,
            &format!(
                "/v1/admin/dashboard/diff?leftBackupId={}&rightBackupId={}",
                bid1, bid2
            ),
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(diff_res["summaryDelta"]["billCountDiff"], 8);
    assert_eq!(
        diff_res["summaryDelta"]["netWorthDiffCents"],
        358300 - 100000
    );

    // 8. 管理员文件卷只读巡检 + 下载 + 确认不修改 devices.last_seen_at
    let (st, _, st_audit) = env
        .json_req(Method::GET, "/v1/admin/storage-audit", &admin_hdr, None)
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(st_audit["indexedCount"], 2);

    let (st, _, dl_bytes) = env
        .send(
            Request::builder()
                .method(Method::GET)
                .uri(format!("/v1/admin/backups/{}/download", bid2))
                .header("x-admin-token", env.admin_token.as_str())
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    assert_eq!(st, StatusCode::OK);
    assert_eq!(dl_bytes, raw2);

    let after_seen: Option<chrono::DateTime<Utc>> =
        sqlx::query("SELECT last_seen_at FROM devices WHERE id=$1")
            .bind(dev_id)
            .fetch_one(&env.pool)
            .await
            .unwrap()
            .try_get("last_seen_at")
            .unwrap();
    assert!(after_seen.is_none());

    // 9. 损坏快照返回 409
    let space_id = p["spaceId"].as_str().unwrap();
    let bad_path = env
        .backup_dir
        .path()
        .join(space_id)
        .join(format!("{}.json", bid2));
    fs::write(&bad_path, b"damaged").unwrap();
    let (st, _, _) = env
        .json_req(
            Method::GET,
            &format!("/v1/admin/dashboard/overview?backupId={}", bid2),
            &admin_hdr,
            None,
        )
        .await;
    assert_eq!(st, StatusCode::CONFLICT);

    // 10. 单二进制内嵌静态首页、严苛 CSP（无 unsafe-inline）与静态资源响应头校验
    let (st, hdrs, _) = env
        .send(
            Request::builder()
                .method(Method::GET)
                .uri("/admin")
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    assert_eq!(st, StatusCode::TEMPORARY_REDIRECT);
    assert_eq!(
        hdrs.get("location").and_then(|v| v.to_str().ok()),
        Some("/admin/cloud-backup")
    );

    for ui_path in ["/admin/cloud-backup", "/admin/cloud-backup/"] {
        let (st, hdrs, html_bytes) = env
            .send(
                Request::builder()
                    .method(Method::GET)
                    .uri(ui_path)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await;
        assert_eq!(st, StatusCode::OK);
        assert_eq!(
            hdrs.get("content-type").and_then(|v| v.to_str().ok()),
            Some("text/html; charset=utf-8")
        );
        assert_eq!(
            hdrs.get("cache-control").and_then(|v| v.to_str().ok()),
            Some("no-store")
        );
        let csp = hdrs
            .get("content-security-policy")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        assert!(!csp.contains("unsafe-inline"));
        assert!(csp.contains("script-src 'self'"));
        assert!(csp.contains("style-src 'self'"));

        let html = String::from_utf8(html_bytes).unwrap();
        for icon in ["favicon.png", "apple-touch-icon.png"] {
            assert!(
                html.contains(&format!("/admin/cloud-backup/{icon}")),
                "index.html 未引用站点图标 {icon}"
            );
            let (ist, ihdrs, ibody) = env
                .send(
                    Request::builder()
                        .method(Method::GET)
                        .uri(format!("/admin/cloud-backup/{icon}"))
                        .body(Body::empty())
                        .unwrap(),
                )
                .await;
            assert_eq!(ist, StatusCode::OK);
            assert_eq!(
                ihdrs.get("content-type").and_then(|v| v.to_str().ok()),
                Some("image/png")
            );
            assert!(!ibody.is_empty());
        }
        if let Some(start) = html.find("/admin/cloud-backup/assets/") {
            let rest = &html[start + "/admin/cloud-backup/".len()..];
            let asset_rel = rest.split('"').next().unwrap();
            let (ast, ahdrs, abody) = env
                .send(
                    Request::builder()
                        .method(Method::GET)
                        .uri(format!("/admin/cloud-backup/{}", asset_rel))
                        .body(Body::empty())
                        .unwrap(),
                )
                .await;
            assert_eq!(ast, StatusCode::OK);
            assert!(!abody.is_empty());
            assert_eq!(
                ahdrs.get("cache-control").and_then(|v| v.to_str().ok()),
                Some("private, max-age=31536000, immutable")
            );
        }
    }

    env.cleanup().await;
}

#[test]
fn real_sample_snapshot_smoke_benchmark() {
    let sample = Path::new("/Users/caozl/mymoney-home/backups/59d006fa-666f-4bfa-a580-0580cfc61467/8295af0f-af50-461d-b55b-9e57e1161e17.json");
    if !sample.is_file() {
        return;
    }
    let meta = SnapshotMeta {
        backup_id: Uuid::new_v4(),
        space_id: Uuid::new_v4(),
        device_id: Uuid::new_v4(),
        device_name: "小米14".to_string(),
        app_version: "1.10.3/162".to_string(),
        backup_schema: 12,
        sha256: "0".repeat(64),
        size_bytes: fs::metadata(sample).unwrap().len() as i64,
        bill_count: 2349,
        snapshot_at: Utc::now(),
        uploaded_at: Utc::now(),
        storage_key: "sample.json".to_string(),
        overlay_revision: 0,
        overlay_updated_at: None,
        overlay_count: 0,
    };

    let t0 = Instant::now();
    let snap = parse_snapshot_from_file(sample, meta).expect("parse real backup");
    let parse_ms = t0.elapsed().as_secs_f64() * 1000.0;
    let n = snap.bills.len();
    assert!(n >= 2348);
    assert!((1..=31).contains(&snap.month_start_day));
    assert_eq!(snap.accounts.len(), 6);

    let t1 = Instant::now();
    let ov = build_overview(&snap, "ledger", None, None, Some("Asia/Shanghai")).unwrap();
    let an = build_analytics(&snap, "ledger", None, None, None, Some("Asia/Shanghai")).unwrap();
    let as_res = build_assets(&snap, None, None, None, Shanghai).unwrap();
    let au_res = build_audit(&snap, Shanghai);
    let q_res = query_bills(&snap, &BillFilter::default(), Shanghai);
    let query_ms = t1.elapsed().as_secs_f64() * 1000.0;

    let timeline = as_res["timeline"].as_array().unwrap();
    assert_eq!(
        timeline.last().unwrap()["netCents"],
        as_res["summary"]["netCents"]
    );
    assert_eq!(q_res["total"], n);
    assert!(ov["totals"].is_object());
    assert!(an["calendarHeatmap"]["days"].as_array().unwrap().len() >= 360);
    assert_eq!(au_res["deletions"].as_array().unwrap().len(), 3);

    println!(
        "real_sample_snapshot: {} bills parsed in {:.2} ms, all 5 analytics queries in {:.2} ms",
        n, parse_ms, query_ms
    );
}
