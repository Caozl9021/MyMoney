use std::collections::{HashMap, HashSet};
use std::fs::{self, File};
use std::io::BufReader;
use std::num::NonZeroUsize;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

use chrono::{DateTime, Utc};
use lru::LruCache;
use serde_json::{Map, Value};
use sqlx::{PgPool, Row};
use uuid::Uuid;

use crate::backup::storage::{backup_file_path, file_digest};
use crate::error::AppError;

pub const MAX_CACHED_SNAPSHOTS: usize = 6;

#[derive(Clone, Debug)]
pub struct SnapshotMeta {
    pub backup_id: Uuid,
    pub space_id: Uuid,
    pub device_id: Uuid,
    pub device_name: String,
    pub app_version: String,
    pub backup_schema: i32,
    pub sha256: String,
    pub size_bytes: i64,
    pub bill_count: i64,
    pub snapshot_at: DateTime<Utc>,
    pub uploaded_at: DateTime<Utc>,
    pub storage_key: String,
    /// 该设备在 `ledger_items` 中的最新增量修订号；0 = 没有叠加实时增量。
    pub overlay_revision: i64,
    /// 最新一条增量的内容时间（content_updated_at / deleted_at 的较大者）。
    pub overlay_updated_at: Option<DateTime<Utc>>,
    /// 本次 Overlay 实际叠加的增量行数。
    pub overlay_count: i64,
}

#[derive(Clone, Debug)]
pub struct ParsedBill {
    pub index: usize,
    pub price_cents: i64,
    pub bill_type: String,
    pub time_millis: i64,
    pub created_at: i64,
    pub merchant: Option<String>,
    pub method: Option<String>,
    pub order_tail: Option<String>,
    pub source_pkg: String,
    pub strategy: String,
    pub fingerprint: String,
    pub category: Option<String>,
    pub sub_category: Option<String>,
    pub account: Option<String>,
    pub note: Option<String>,
    pub product_name: Option<String>,
    pub product_source: Option<String>,
    pub note_origin: Option<String>,
    pub note_template_snapshot: Option<String>,
    pub recognition_id: Option<String>,
    pub local_id: Option<i64>,
    pub account_id: Option<String>,
    pub transfer_account_id: Option<String>,
    pub asset_linked: bool,
    pub asset_key: String,
    pub transfer_fee_cents: i64,
    pub fee_parent_key: Option<String>,
    pub transfer_account: Option<String>,
    pub sync_id: String,
    pub reimburse_status: String,
    pub reimbursed_at: Option<i64>,
    pub reimburse_account_id: Option<String>,
    pub resolved_category: String,
    pub resolved_account: String,
    pub resolved_transfer_account: Option<String>,
    pub channel_label: String,
    pub counts_in_expense: bool,
}

#[derive(Clone, Debug)]
pub struct ParsedAccount {
    pub id: String,
    pub name: String,
    pub kind: String,
    pub aliases: Vec<String>,
    pub sort_order: i32,
    pub archived: bool,
    pub included: bool,
    pub initialized: bool,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Clone, Debug)]
pub struct ParsedEntry {
    pub id: String,
    pub operation_id: String,
    pub account_id: String,
    pub delta_cents: i64,
    pub kind: String,
    pub note: String,
    pub created_at: i64,
    pub bill_key: Option<String>,
    pub reversal_of: Option<String>,
}

#[derive(Clone, Debug)]
pub struct ParsedDeletion {
    pub event_id: String,
    pub deleted_at: i64,
    pub origin: String,
    pub principal_id: i64,
    pub recognition_id: Option<String>,
    pub group_id: String,
    pub bills: Vec<Value>,
}

#[derive(Debug, Clone)]
pub struct ParsedSnapshot {
    pub meta: SnapshotMeta,
    pub exported_at: i64,
    pub month_start_day: u32,
    pub expense_categories: Vec<(String, Vec<String>)>,
    pub income_categories: Vec<(String, Vec<String>)>,
    pub bills: Vec<ParsedBill>,
    pub accounts: Vec<ParsedAccount>,
    pub entries: Vec<ParsedEntry>,
    pub deletions: Vec<ParsedDeletion>,
}

struct CachedSnapshot {
    snapshot: Arc<ParsedSnapshot>,
    modified: Option<SystemTime>,
}

/// 缓存键 `(backup_id, sha256, overlay_revision)`：没有新增量时 revision 不变，
/// 同一份基准快照 + 同一批增量永远命中同一份内存结果（0 磁盘 IO、0 重复解析）。
pub type SnapshotCacheKey = (Uuid, String, i64);

pub struct SnapshotCache {
    inner: Mutex<LruCache<SnapshotCacheKey, CachedSnapshot>>,
}

impl SnapshotCache {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(LruCache::new(
                NonZeroUsize::new(MAX_CACHED_SNAPSHOTS).expect("cache capacity > 0"),
            )),
        }
    }
}

impl Default for SnapshotCache {
    fn default() -> Self {
        Self::new()
    }
}

fn opt_non_blank(val: Option<&Value>) -> Option<String> {
    val.and_then(|v| v.as_str())
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string())
}

fn opt_raw_str(val: Option<&Value>) -> Option<String> {
    val.and_then(|v| v.as_str()).map(|s| s.to_string())
}

fn normalize_reimburse_status(raw: Option<&str>) -> String {
    match raw.unwrap_or("NONE") {
        "PENDING" => "PENDING".to_string(),
        "REIMBURSED" => "REIMBURSED".to_string(),
        _ => "NONE".to_string(),
    }
}

fn classify_channel(source_pkg: &str, strategy: &str) -> String {
    let sp = source_pkg.to_lowercase();
    let st = strategy.to_lowercase();
    if st.contains("qianji") || sp.contains("qianji") {
        return "钱迹导入".to_string();
    }
    if st.contains("icost") || sp.contains("icost") {
        return "iCost导入".to_string();
    }
    if sp.is_empty() || st == "manual" || st == "backup" {
        return "手动记账".to_string();
    }
    if sp.contains("alipay") || sp.contains("eg.android.alipaygphone") {
        return "支付宝".to_string();
    }
    if sp.contains("tencent.mm") || sp.contains("wechat") {
        return "微信".to_string();
    }
    if sp.contains("unionpay") || sp.contains("cloudpay") {
        return "云闪付".to_string();
    }
    if st.starts_with("notification:") {
        return "通知识别".to_string();
    }
    if st.starts_with("spec:") {
        return "规则识别".to_string();
    }
    "其他来源".to_string()
}

pub fn parse_snapshot_from_file(
    path: &Path,
    meta: SnapshotMeta,
) -> Result<ParsedSnapshot, AppError> {
    let file =
        File::open(path).map_err(|_| AppError::conflict("备份文件不可用，请恢复家庭服务文件卷"))?;
    let reader = BufReader::new(file);
    let data: Value = serde_json::from_reader(reader)
        .map_err(|_| AppError::unprocessable("备份快照 JSON 格式无法解析"))?;
    let root = data
        .as_object()
        .ok_or_else(|| AppError::unprocessable("备份文件不是 JSON 对象"))?;

    let format = root.get("format").and_then(|v| v.as_i64()).unwrap_or(1) as i32;
    let exported_at = root.get("exportedAt").and_then(|v| v.as_i64()).unwrap_or(0);

    // 解析 ledger 配置
    let ledger = root.get("ledger").and_then(|v| v.as_object());
    let month_start_day = ledger
        .and_then(|l| l.get("monthStartDay"))
        .and_then(|v| v.as_i64())
        .filter(|&d| (1..=31).contains(&d))
        .unwrap_or(1) as u32;

    let parse_groups = |key: &str| -> Vec<(String, Vec<String>)> {
        ledger
            .and_then(|l| l.get(key))
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|item| {
                        let m = item.as_object()?;
                        let name = m.get("n")?.as_str()?.trim().to_string();
                        if name.is_empty() {
                            return None;
                        }
                        let subs = m
                            .get("s")
                            .and_then(|v| v.as_array())
                            .map(|s_arr| {
                                s_arr
                                    .iter()
                                    .filter_map(|s| s.as_str().map(|x| x.to_string()))
                                    .collect()
                            })
                            .unwrap_or_default();
                        Some((name, subs))
                    })
                    .collect()
            })
            .unwrap_or_default()
    };

    let expense_categories = parse_groups("expense");
    let income_categories = parse_groups("income");

    // 解析 assets.accounts 与 assets.entries
    let assets_obj = root.get("assets").and_then(|v| v.as_object());
    let mut accounts = Vec::new();
    let mut account_name_by_id: HashMap<String, String> = HashMap::new();
    if let Some(acc_arr) = assets_obj
        .and_then(|a| a.get("accounts"))
        .and_then(|v| v.as_array())
    {
        for item in acc_arr {
            let Some(m) = item.as_object() else { continue };
            let Some(id) = opt_raw_str(m.get("id")) else {
                continue;
            };
            let name = opt_raw_str(m.get("name")).unwrap_or_else(|| "未命名账户".to_string());
            let kind = opt_raw_str(m.get("kind")).unwrap_or_else(|| "PAYMENT".to_string());
            let aliases = m
                .get("aliases")
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|s| s.as_str().map(|x| x.to_string()))
                        .collect()
                })
                .unwrap_or_default();
            let sort_order = m.get("sortOrder").and_then(|v| v.as_i64()).unwrap_or(0) as i32;
            let archived = m.get("archived").and_then(|v| v.as_bool()).unwrap_or(false);
            let included = m.get("included").and_then(|v| v.as_bool()).unwrap_or(true);
            let initialized = m
                .get("initialized")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            let created_at = m.get("createdAt").and_then(|v| v.as_i64()).unwrap_or(0);
            let updated_at = m
                .get("updatedAt")
                .and_then(|v| v.as_i64())
                .unwrap_or(created_at);

            account_name_by_id.insert(id.clone(), name.clone());
            accounts.push(ParsedAccount {
                id,
                name,
                kind,
                aliases,
                sort_order,
                archived,
                included,
                initialized,
                created_at,
                updated_at,
            });
        }
    }

    let mut entries = Vec::new();
    if let Some(ent_arr) = assets_obj
        .and_then(|a| a.get("entries"))
        .and_then(|v| v.as_array())
    {
        for item in ent_arr {
            let Some(m) = item.as_object() else { continue };
            let Some(id) = opt_raw_str(m.get("id")) else {
                continue;
            };
            let operation_id = opt_raw_str(m.get("operationId")).unwrap_or_default();
            let Some(account_id) = opt_raw_str(m.get("accountId")) else {
                continue;
            };
            let delta_cents = m.get("deltaCents").and_then(|v| v.as_i64()).unwrap_or(0);
            let kind = opt_raw_str(m.get("kind")).unwrap_or_else(|| "ADJUSTMENT".to_string());
            let note = opt_raw_str(m.get("note")).unwrap_or_default();
            let created_at = m.get("createdAt").and_then(|v| v.as_i64()).unwrap_or(0);
            let bill_key = opt_non_blank(m.get("billKey"));
            let reversal_of = opt_non_blank(m.get("reversalOf"));

            entries.push(ParsedEntry {
                id,
                operation_id,
                account_id,
                delta_cents,
                kind,
                note,
                created_at,
                bill_key,
                reversal_of,
            });
        }
    }

    // 解析 bills
    let raw_bills = root
        .get("bills")
        .and_then(|v| v.as_array())
        .ok_or_else(|| AppError::unprocessable("备份快照缺少 bills 数组"))?;

    let mut bills = Vec::with_capacity(raw_bills.len());
    for (idx, item) in raw_bills.iter().enumerate() {
        let Some(m) = item.as_object() else { continue };
        let price_cents = m.get("p").and_then(|v| v.as_i64()).unwrap_or(0);
        let bill_type = opt_raw_str(m.get("ty")).unwrap_or_else(|| "EXPENSE".to_string());
        let time_millis = m.get("t").and_then(|v| v.as_i64()).unwrap_or(0);
        let created_at = m
            .get("ca")
            .and_then(|v| v.as_i64())
            .filter(|&v| v != 0)
            .unwrap_or(time_millis);

        let category = opt_non_blank(m.get("cg"));
        let sub_category = opt_non_blank(m.get("sc"));
        let account = opt_non_blank(m.get("ac"));
        let account_id = if format >= 3 {
            opt_non_blank(m.get("aid"))
        } else {
            None
        };
        let transfer_account_id = if format >= 3 {
            opt_non_blank(m.get("taid"))
        } else {
            None
        };
        let transfer_account = if format >= 5 {
            opt_non_blank(m.get("tac"))
        } else {
            None
        };

        let resolved_category = category.clone().unwrap_or_else(|| "未分类".to_string());
        let resolved_account = account_id
            .as_ref()
            .and_then(|aid| account_name_by_id.get(aid))
            .cloned()
            .or_else(|| account.clone())
            .unwrap_or_else(|| "未知账户".to_string());
        let resolved_transfer_account = transfer_account_id
            .as_ref()
            .and_then(|tid| account_name_by_id.get(tid))
            .cloned()
            .or_else(|| transfer_account.clone());

        let reimburse_status = if format >= 11 {
            normalize_reimburse_status(m.get("rs").and_then(|v| v.as_str()))
        } else {
            "NONE".to_string()
        };
        let reimbursed_at = if format >= 11 {
            m.get("rat").and_then(|v| v.as_i64()).filter(|&v| v > 0)
        } else {
            None
        };
        let reimburse_account_id = if format >= 12 {
            opt_non_blank(m.get("raid"))
        } else if format >= 11 && reimburse_status == "REIMBURSED" {
            account_id.clone()
        } else {
            None
        };

        let source_pkg = opt_raw_str(m.get("sp")).unwrap_or_default();
        let strategy = opt_raw_str(m.get("st")).unwrap_or_else(|| "backup".to_string());
        let channel_label = classify_channel(&source_pkg, &strategy);
        let counts_in_expense = bill_type == "EXPENSE" && reimburse_status != "REIMBURSED";

        bills.push(ParsedBill {
            index: idx,
            price_cents,
            bill_type,
            time_millis,
            created_at,
            merchant: opt_non_blank(m.get("me")),
            method: opt_non_blank(m.get("mo")),
            order_tail: opt_non_blank(m.get("ot")),
            source_pkg,
            strategy,
            fingerprint: opt_raw_str(m.get("fp")).unwrap_or_default(),
            category,
            sub_category,
            account,
            note: opt_non_blank(m.get("no")),
            product_name: opt_non_blank(m.get("pn")),
            product_source: opt_non_blank(m.get("ps")),
            note_origin: opt_non_blank(m.get("noOrigin")),
            note_template_snapshot: opt_non_blank(m.get("nt")),
            recognition_id: opt_non_blank(m.get("rid")),
            local_id: m.get("id").and_then(|v| v.as_i64()),
            account_id,
            transfer_account_id,
            asset_linked: if format >= 3 {
                m.get("al").and_then(|v| v.as_bool()).unwrap_or(false)
            } else {
                false
            },
            asset_key: if format >= 3 {
                opt_raw_str(m.get("ak")).unwrap_or_default()
            } else {
                String::new()
            },
            transfer_fee_cents: if format >= 3 {
                m.get("tf").and_then(|v| v.as_i64()).unwrap_or(0)
            } else {
                0
            },
            fee_parent_key: if format >= 3 {
                opt_non_blank(m.get("fpk"))
            } else {
                None
            },
            transfer_account,
            sync_id: if format >= 10 {
                opt_raw_str(m.get("sid")).unwrap_or_default()
            } else {
                String::new()
            },
            reimburse_status,
            reimbursed_at,
            reimburse_account_id,
            resolved_category,
            resolved_account,
            resolved_transfer_account,
            channel_label,
            counts_in_expense,
        });
    }

    // 解析 deletions（只读删除审计日志）
    let mut deletions = Vec::new();
    if let Some(del_arr) = root.get("deletions").and_then(|v| v.as_array()) {
        for ev in del_arr {
            let Some(m) = ev.as_object() else { continue };
            let event_id = opt_raw_str(m.get("eventId")).unwrap_or_default();
            let deleted_at = m.get("deletedAt").and_then(|v| v.as_i64()).unwrap_or(0);
            let origin = opt_raw_str(m.get("origin")).unwrap_or_default();
            let principal_id = m.get("principalId").and_then(|v| v.as_i64()).unwrap_or(0);
            let recognition_id = opt_non_blank(m.get("recognitionId"));
            let group_id = opt_raw_str(m.get("groupId")).unwrap_or_default();
            let snap_bills = m
                .get("snapshotJson")
                .and_then(|v| v.as_str())
                .and_then(|s| serde_json::from_str::<Value>(s).ok())
                .and_then(|v| v.get("bills").and_then(|b| b.as_array()).cloned())
                .unwrap_or_default();
            deletions.push(ParsedDeletion {
                event_id,
                deleted_at,
                origin,
                principal_id,
                recognition_id,
                group_id,
                bills: snap_bills,
            });
        }
    }

    Ok(ParsedSnapshot {
        meta,
        exported_at,
        month_start_day,
        expense_categories,
        income_categories,
        bills,
        accounts,
        entries,
        deletions,
    })
}

/// 一条来自 `ledger_items` 的增量（bill / account / entry / meta）。
#[derive(Clone, Debug)]
pub struct OverlayItem {
    pub id: String,
    pub kind: String,
    pub payload: Value,
    pub revision: i64,
    pub deleted_at: Option<DateTime<Utc>>,
}

fn ov_str(map: &Map<String, Value>, keys: &[&str]) -> Option<String> {
    keys.iter()
        .find_map(|k| map.get(*k).and_then(|v| v.as_str()).map(|s| s.to_string()))
}

fn ov_non_blank(map: &Map<String, Value>, keys: &[&str]) -> Option<String> {
    ov_str(map, keys)
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
}

fn ov_i64(map: &Map<String, Value>, keys: &[&str], default: i64) -> i64 {
    keys.iter()
        .find_map(|k| map.get(*k).and_then(|v| v.as_i64()))
        .unwrap_or(default)
}

fn ov_bool(map: &Map<String, Value>, keys: &[&str], default: bool) -> bool {
    keys.iter()
        .find_map(|k| map.get(*k).and_then(|v| v.as_bool()))
        .unwrap_or(default)
}

fn is_tombstone(row: &OverlayItem) -> bool {
    if row.deleted_at.is_some() {
        return true;
    }
    row.payload
        .get("deletedAt")
        .and_then(|v| v.as_i64())
        .filter(|v| *v > 0)
        .is_some()
}

fn parse_overlay_groups(value: Option<&Value>) -> Vec<(String, Vec<String>)> {
    value
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|item| {
                    let m = item.as_object()?;
                    let name = ov_non_blank(m, &["name", "n"])?;
                    let subs = m
                        .get("subs")
                        .or_else(|| m.get("s"))
                        .and_then(|v| v.as_array())
                        .map(|s_arr| {
                            s_arr
                                .iter()
                                .filter_map(|s| s.as_str().map(|x| x.to_string()))
                                .collect()
                        })
                        .unwrap_or_default();
                    Some((name, subs))
                })
                .collect()
        })
        .unwrap_or_default()
}

fn parse_account_from_overlay_payload(payload: &Value) -> Option<ParsedAccount> {
    let m = payload.as_object()?;
    let id = ov_str(m, &["syncId", "id"])?;
    let aliases = match m.get("aliases") {
        Some(Value::Array(arr)) => arr
            .iter()
            .filter_map(|s| s.as_str().map(|x| x.to_string()))
            .collect(),
        Some(Value::String(text)) => serde_json::from_str::<Value>(text)
            .ok()
            .and_then(|v| v.as_array().cloned())
            .map(|arr| {
                arr.iter()
                    .filter_map(|s| s.as_str().map(|x| x.to_string()))
                    .collect()
            })
            .unwrap_or_default(),
        _ => Vec::new(),
    };
    let created_at = ov_i64(m, &["createdAt"], 0);
    Some(ParsedAccount {
        id,
        name: ov_non_blank(m, &["name"]).unwrap_or_else(|| "未命名账户".to_string()),
        kind: ov_non_blank(m, &["kind"]).unwrap_or_else(|| "PAYMENT".to_string()),
        aliases,
        sort_order: ov_i64(m, &["sortOrder"], 0) as i32,
        archived: ov_bool(m, &["archived"], false),
        included: ov_bool(m, &["included"], true),
        initialized: ov_bool(m, &["initialized"], false),
        created_at,
        updated_at: ov_i64(m, &["updatedAt"], created_at),
    })
}

fn parse_entry_from_overlay_payload(payload: &Value) -> Option<ParsedEntry> {
    let m = payload.as_object()?;
    let id = ov_str(m, &["syncId", "id"])?;
    Some(ParsedEntry {
        id,
        operation_id: ov_str(m, &["operationId"]).unwrap_or_default(),
        account_id: ov_str(m, &["accountId"]).unwrap_or_default(),
        delta_cents: ov_i64(m, &["deltaCents"], 0),
        kind: ov_non_blank(m, &["kind"]).unwrap_or_else(|| "ADJUSTMENT".to_string()),
        note: ov_str(m, &["note"]).unwrap_or_default(),
        created_at: ov_i64(m, &["createdAt"], 0),
        bill_key: ov_non_blank(m, &["billKey"]),
        reversal_of: ov_non_blank(m, &["reversalOf"]),
    })
}

/// 增量 payload 使用可读驼峰键（`LedgerPayloadCodec`），但为兼容备份短键也接受 `p/ty/t/...`。
/// 账户解析口径与 [parse_snapshot_from_file] 完全一致（resolved_* / channel / counts_in_expense）。
fn parse_bill_from_overlay_payload(
    payload: &Map<String, Value>,
    account_by_id: &HashMap<String, String>,
    index: usize,
) -> Option<ParsedBill> {
    let sync_id = ov_str(payload, &["syncId", "sid"])?;
    let price_cents = ov_i64(payload, &["priceCents", "p"], 0);
    let bill_type = ov_str(payload, &["type", "ty"]).unwrap_or_else(|| "EXPENSE".to_string());
    let time_millis = ov_i64(payload, &["timeMillis", "t"], 0);
    let created_at = ov_i64(payload, &["createdAt", "ca"], 0);
    let created_at = if created_at == 0 {
        time_millis
    } else {
        created_at
    };

    let category = ov_non_blank(payload, &["category", "cg"]);
    let sub_category = ov_non_blank(payload, &["subCategory", "sc"]);
    let account = ov_non_blank(payload, &["account", "ac"]);
    let account_id = ov_non_blank(payload, &["accountId", "aid"]);
    let transfer_account_id = ov_non_blank(payload, &["transferAccountId", "taid"]);
    let transfer_account = ov_non_blank(payload, &["transferAccount", "tac"]);

    let resolved_category = category.clone().unwrap_or_else(|| "未分类".to_string());
    let resolved_account = account_id
        .as_ref()
        .and_then(|aid| account_by_id.get(aid))
        .cloned()
        .or_else(|| account.clone())
        .unwrap_or_else(|| "未知账户".to_string());
    let resolved_transfer_account = transfer_account_id
        .as_ref()
        .and_then(|tid| account_by_id.get(tid))
        .cloned()
        .or_else(|| transfer_account.clone());

    let reimburse_status =
        normalize_reimburse_status(ov_str(payload, &["reimburseStatus", "rs"]).as_deref());
    let reimbursed_at = payload
        .get("reimbursedAt")
        .or_else(|| payload.get("rat"))
        .and_then(|v| v.as_i64())
        .filter(|v| *v > 0);
    let reimburse_account_id =
        ov_non_blank(payload, &["reimburseAccountId", "raid"]).or_else(|| {
            if reimburse_status == "REIMBURSED" {
                account_id.clone()
            } else {
                None
            }
        });

    let source_pkg = ov_str(payload, &["sourcePkg", "sp"]).unwrap_or_default();
    let strategy = ov_str(payload, &["strategy", "st"]).unwrap_or_else(|| "sync".to_string());
    let channel_label = classify_channel(&source_pkg, &strategy);
    let counts_in_expense = bill_type == "EXPENSE" && reimburse_status != "REIMBURSED";

    Some(ParsedBill {
        index,
        price_cents,
        bill_type,
        time_millis,
        created_at,
        merchant: ov_non_blank(payload, &["merchant", "me"]),
        method: ov_non_blank(payload, &["method", "mo"]),
        order_tail: ov_non_blank(payload, &["orderTail", "ot"]),
        source_pkg,
        strategy,
        fingerprint: ov_str(payload, &["fingerprint", "fp"]).unwrap_or_default(),
        category,
        sub_category,
        account,
        note: ov_non_blank(payload, &["note", "no"]),
        product_name: ov_non_blank(payload, &["productName", "pn"]),
        product_source: ov_non_blank(payload, &["productSource", "ps"]),
        note_origin: ov_non_blank(payload, &["noteOrigin", "noOrigin"]),
        note_template_snapshot: ov_non_blank(payload, &["noteTemplateSnapshot", "nt"]),
        recognition_id: ov_non_blank(payload, &["recognitionId", "rid"]),
        local_id: payload
            .get("id")
            .or_else(|| payload.get("localId"))
            .and_then(|v| v.as_i64()),
        account_id,
        transfer_account_id,
        asset_linked: ov_bool(payload, &["assetLinked", "al"], false),
        asset_key: ov_str(payload, &["assetKey", "ak"]).unwrap_or_default(),
        transfer_fee_cents: ov_i64(payload, &["transferFeeCents", "tf"], 0),
        fee_parent_key: ov_non_blank(payload, &["feeParentKey", "fpk"]),
        transfer_account,
        sync_id,
        reimburse_status,
        reimbursed_at,
        reimburse_account_id,
        resolved_category,
        resolved_account,
        resolved_transfer_account,
        channel_label,
        counts_in_expense,
    })
}

fn overlay_sync_id(row: &OverlayItem, prefix: &str) -> String {
    row.payload
        .as_object()
        .and_then(|m| ov_str(m, &["syncId", "id", "sid"]))
        .or_else(|| row.id.strip_prefix(prefix).map(|s| s.to_string()))
        .unwrap_or_else(|| row.id.clone())
}

/// 在内存里把 `ledger_items` 的增量叠加到已解析的基准快照上。返回叠加的条目数。
///
/// 叠加顺序：meta → account → entry → bill；账户名解析使用叠加后的账户表，
/// 账单最后按 `(timeMillis, 原序号)` 重排并重编号 `index`。
pub fn apply_ledger_overlay(parsed: &mut ParsedSnapshot, rows: &[OverlayItem]) -> usize {
    let mut applied = 0usize;

    // 1) meta:config —— 分类树与记账周期起始日
    if let Some(item) = rows
        .iter()
        .rev()
        .find(|r| r.kind == "meta" && r.deleted_at.is_none())
    {
        if let Some(m) = item.payload.as_object() {
            if let Some(day) = m
                .get("monthStartDay")
                .and_then(|v| v.as_i64())
                .filter(|d| (1..=31).contains(d))
            {
                parsed.month_start_day = day as u32;
            }
            if m.contains_key("expenseCategories") {
                parsed.expense_categories = parse_overlay_groups(m.get("expenseCategories"));
            }
            if m.contains_key("incomeCategories") {
                parsed.income_categories = parse_overlay_groups(m.get("incomeCategories"));
            }
        }
        applied += 1;
    }

    // 2) 资产账户
    let mut accounts: HashMap<String, ParsedAccount> = std::mem::take(&mut parsed.accounts)
        .into_iter()
        .map(|a| (a.id.clone(), a))
        .collect();
    for row in rows.iter().filter(|r| r.kind == "account") {
        let id = overlay_sync_id(row, "account:");
        if is_tombstone(row) {
            accounts.remove(&id);
        } else if let Some(acc) = parse_account_from_overlay_payload(&row.payload) {
            accounts.insert(acc.id.clone(), acc);
        }
        applied += 1;
    }
    let mut account_list: Vec<ParsedAccount> = accounts.into_values().collect();
    account_list.sort_by(|a, b| {
        (a.sort_order, a.created_at, a.id.as_str()).cmp(&(
            b.sort_order,
            b.created_at,
            b.id.as_str(),
        ))
    });
    let account_by_id: HashMap<String, String> = account_list
        .iter()
        .map(|a| (a.id.clone(), a.name.clone()))
        .collect();
    parsed.accounts = account_list;

    // 3) 资产余额流水
    let mut entries: HashMap<String, ParsedEntry> = std::mem::take(&mut parsed.entries)
        .into_iter()
        .map(|e| (e.id.clone(), e))
        .collect();
    for row in rows.iter().filter(|r| r.kind == "entry") {
        let id = overlay_sync_id(row, "entry:");
        if is_tombstone(row) {
            entries.remove(&id);
        } else if let Some(entry) = parse_entry_from_overlay_payload(&row.payload) {
            entries.insert(entry.id.clone(), entry);
        }
        applied += 1;
    }
    let mut entry_list: Vec<ParsedEntry> = entries.into_values().collect();
    entry_list.sort_by(|a, b| (a.created_at, a.id.as_str()).cmp(&(b.created_at, b.id.as_str())));
    parsed.entries = entry_list;

    // 4) 账单
    let mut bills: Vec<(usize, ParsedBill)> = std::mem::take(&mut parsed.bills)
        .into_iter()
        .enumerate()
        .collect();
    let mut index_by_sync: HashMap<String, usize> = HashMap::new();
    for (pos, (_, bill)) in bills.iter().enumerate() {
        if !bill.sync_id.is_empty() {
            index_by_sync.insert(bill.sync_id.clone(), pos);
        }
    }
    let mut removed: HashSet<String> = HashSet::new();
    let mut next_seq = bills.len();
    for row in rows.iter().filter(|r| r.kind == "bill") {
        let sync_id = overlay_sync_id(row, "bill:");
        let pos = index_by_sync.get(&sync_id).copied();
        if is_tombstone(row) {
            if pos.is_some() {
                removed.insert(sync_id);
            }
        } else if let Some(payload) = row.payload.as_object() {
            if let Some(bill) = parse_bill_from_overlay_payload(payload, &account_by_id, 0) {
                match pos {
                    Some(p) => {
                        removed.remove(&bill.sync_id);
                        bills[p].1 = bill;
                    }
                    None => {
                        index_by_sync.insert(bill.sync_id.clone(), bills.len());
                        bills.push((next_seq, bill));
                        next_seq += 1;
                    }
                }
            }
        }
        applied += 1;
    }

    let mut merged: Vec<(usize, ParsedBill)> = bills
        .into_iter()
        .filter(|(_, b)| !removed.contains(&b.sync_id))
        .collect();
    merged.sort_by(|(s1, a), (s2, b)| a.time_millis.cmp(&b.time_millis).then_with(|| s1.cmp(s2)));
    let mut final_bills = Vec::with_capacity(merged.len());
    for (index, (_, mut bill)) in merged.into_iter().enumerate() {
        bill.index = index;
        final_bills.push(bill);
    }
    parsed.meta.bill_count = final_bills.len() as i64;
    parsed.bills = final_bills;

    applied
}

async fn is_latest_snapshot_for_device(
    pool: &PgPool,
    space_id: Uuid,
    device_id: Uuid,
    backup_id: Uuid,
) -> Result<bool, AppError> {
    let row = sqlx::query(
        r#"SELECT id FROM ledger_backups
           WHERE space_id=$1 AND device_id=$2 AND deleted_at IS NULL
           ORDER BY uploaded_at DESC, id DESC LIMIT 1"#,
    )
    .bind(space_id)
    .bind(device_id)
    .fetch_optional(pool)
    .await?;
    Ok(match row {
        Some(r) => r.try_get::<Uuid, _>("id")? == backup_id,
        None => false,
    })
}

async fn overlay_state(
    pool: &PgPool,
    space_id: Uuid,
    ledger_id: Uuid,
    exported_at_ms: i64,
) -> Result<(i64, Option<DateTime<Utc>>), AppError> {
    // 只统计真正需要叠加的行：墓碑（删除事件在快照之后或之前都无害）与内容晚于基准快照的
    // 增量；早于快照的非删除行已经包含在快照里，不再重复覆盖。
    let row = sqlx::query(
        r#"SELECT COALESCE(MAX(revision), 0) AS rev,
                  MAX(content_updated_at) AS content_ts,
                  MAX(deleted_at) AS deleted_ts
           FROM ledger_items WHERE space_id=$1 AND ledger_id=$2
             AND (deleted_at IS NOT NULL OR content_updated_at IS NULL OR content_updated_at > $3)"#,
    )
    .bind(space_id)
    .bind(ledger_id)
    .bind(exported_at_ms)
    .fetch_one(pool)
    .await?;
    let rev: i64 = row.try_get("rev")?;
    let content_ts: Option<i64> = row.try_get("content_ts")?;
    let deleted_ts: Option<DateTime<Utc>> = row.try_get("deleted_ts")?;
    let content_dt = content_ts.and_then(DateTime::from_timestamp_millis);
    let updated = match (content_dt, deleted_ts) {
        (Some(a), Some(b)) => Some(a.max(b)),
        (Some(a), None) => Some(a),
        (None, Some(b)) => Some(b),
        (None, None) => None,
    };
    Ok((rev, updated))
}

async fn fetch_overlay_rows(
    pool: &PgPool,
    space_id: Uuid,
    ledger_id: Uuid,
    exported_at_ms: i64,
) -> Result<Vec<OverlayItem>, AppError> {
    let rows = sqlx::query(
        r#"SELECT id, item_kind, payload, revision, deleted_at
           FROM ledger_items WHERE space_id=$1 AND ledger_id=$2
             AND (deleted_at IS NOT NULL OR content_updated_at IS NULL OR content_updated_at > $3)
           ORDER BY revision ASC"#,
    )
    .bind(space_id)
    .bind(ledger_id)
    .bind(exported_at_ms)
    .fetch_all(pool)
    .await?;

    let mut items = Vec::with_capacity(rows.len());
    for r in &rows {
        items.push(OverlayItem {
            id: r.try_get("id")?,
            kind: r.try_get("item_kind")?,
            payload: r.try_get("payload")?,
            revision: r.try_get("revision")?,
            deleted_at: r.try_get("deleted_at")?,
        });
    }
    Ok(items)
}

pub async fn fetch_snapshot_meta(
    pool: &PgPool,
    backup_id: Option<Uuid>,
) -> Result<SnapshotMeta, AppError> {
    let row = match backup_id {
        Some(bid) => {
            sqlx::query(
                r#"SELECT b.id, b.space_id, b.device_id, d.name AS device_name, b.app_version,
                          b.backup_schema, b.sha256, b.size_bytes, b.bill_count,
                          b.snapshot_at, b.uploaded_at, b.storage_key
                   FROM ledger_backups b
                   JOIN devices d ON d.space_id=b.space_id AND d.id=b.device_id
                   WHERE b.id=$1 AND b.deleted_at IS NULL
                   LIMIT 1"#,
            )
            .bind(bid)
            .fetch_optional(pool)
            .await?
        }
        None => {
            sqlx::query(
                r#"SELECT b.id, b.space_id, b.device_id, d.name AS device_name, b.app_version,
                          b.backup_schema, b.sha256, b.size_bytes, b.bill_count,
                          b.snapshot_at, b.uploaded_at, b.storage_key
                   FROM ledger_backups b
                   JOIN devices d ON d.space_id=b.space_id AND d.id=b.device_id
                   WHERE b.deleted_at IS NULL
                   ORDER BY b.uploaded_at DESC, b.id DESC
                   LIMIT 1"#,
            )
            .fetch_optional(pool)
            .await?
        }
    };

    let Some(r) = row else {
        return Err(match backup_id {
            Some(_) => AppError::not_found("所选备份快照不存在或已删除"),
            None => AppError::not_found("暂无有效备份快照"),
        });
    };

    let sha: String = r.try_get("sha256")?;
    Ok(SnapshotMeta {
        backup_id: r.try_get("id")?,
        space_id: r.try_get("space_id")?,
        device_id: r.try_get("device_id")?,
        device_name: r.try_get("device_name")?,
        app_version: r.try_get("app_version")?,
        backup_schema: r.try_get("backup_schema")?,
        sha256: sha.trim().to_string(),
        size_bytes: r.try_get("size_bytes")?,
        bill_count: r.try_get("bill_count")?,
        snapshot_at: r.try_get("snapshot_at")?,
        uploaded_at: r.try_get("uploaded_at")?,
        storage_key: r.try_get("storage_key")?,
        overlay_revision: 0,
        overlay_updated_at: None,
        overlay_count: 0,
    })
}

/// 读取并 LRU 缓存不可变快照（严格只读、校验完整性，不修改 devices.last_seen_at）。
pub async fn load_snapshot(
    pool: &PgPool,
    backup_dir: &Path,
    cache: &SnapshotCache,
    backup_id: Option<Uuid>,
) -> Result<Arc<ParsedSnapshot>, AppError> {
    let mut meta = fetch_snapshot_meta(pool, backup_id).await?;
    // 只有「该设备最新未删除快照」才叠加实时增量；历史旧快照与 Diff 保持原始静态。
    let is_latest =
        is_latest_snapshot_for_device(pool, meta.space_id, meta.device_id, meta.backup_id).await?;
    let exported_at_ms = meta.snapshot_at.timestamp_millis();
    let (overlay_revision, overlay_updated_at) = if is_latest {
        overlay_state(pool, meta.space_id, meta.device_id, exported_at_ms).await?
    } else {
        (0, None)
    };
    meta.overlay_revision = overlay_revision;
    meta.overlay_updated_at = overlay_updated_at;

    let cache_key = (meta.backup_id, meta.sha256.clone(), overlay_revision);
    let path = backup_file_path(backup_dir, &meta.storage_key)?;

    let fmeta = fs::metadata(&path).map_err(|_| {
        let mut guard = cache.inner.lock().expect("snapshot cache poisoned");
        guard.pop(&cache_key);
        AppError::conflict("备份文件不可用，请恢复家庭服务文件卷")
    })?;
    if !fmeta.is_file() || fmeta.len() as i64 != meta.size_bytes {
        let mut guard = cache.inner.lock().expect("snapshot cache poisoned");
        guard.pop(&cache_key);
        return Err(AppError::conflict("备份文件损坏，请恢复家庭服务文件卷"));
    }
    let cur_mtime = fmeta.modified().ok();

    {
        let mut guard = cache.inner.lock().expect("snapshot cache poisoned");
        if let Some(hit) = guard.get(&cache_key) {
            if cur_mtime.is_some() && hit.modified == cur_mtime {
                return Ok(Arc::clone(&hit.snapshot));
            }
        }
    }

    if file_digest(&path).ok().as_deref() != Some(meta.sha256.as_str()) {
        let mut guard = cache.inner.lock().expect("snapshot cache poisoned");
        guard.pop(&cache_key);
        return Err(AppError::conflict("备份文件损坏，请恢复家庭服务文件卷"));
    }

    // Overlay 行在解析前取好；查询量极小（自上次完整备份以来的差量）。
    let overlay_rows = if overlay_revision > 0 {
        fetch_overlay_rows(pool, meta.space_id, meta.device_id, exported_at_ms).await?
    } else {
        Vec::new()
    };

    let mut parsed = tokio::task::spawn_blocking(move || parse_snapshot_from_file(&path, meta))
        .await
        .map_err(|_| AppError::internal("解析快照任务异常"))??;
    if overlay_revision > 0 {
        let applied = apply_ledger_overlay(&mut parsed, &overlay_rows);
        parsed.meta.overlay_count = applied as i64;
    }
    let arc = Arc::new(parsed);

    {
        let mut guard = cache.inner.lock().expect("snapshot cache poisoned");
        guard.put(
            cache_key,
            CachedSnapshot {
                snapshot: Arc::clone(&arc),
                modified: cur_mtime,
            },
        );
    }

    Ok(arc)
}
