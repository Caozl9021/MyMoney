use std::collections::HashSet;
use std::fs::File;
use std::io::BufReader;
use std::path::Path;

use chrono::{DateTime, Utc};
use serde_json::{Map, Value};
use uuid::Uuid;

use crate::error::AppError;

const VALIDATION_ERROR: &str = "备份不是有效的 JSON8/JSON9/JSON10 快照；请检查文件或升级家庭服务";

fn invalid() -> AppError {
    AppError::unprocessable(VALIDATION_ERROR)
}

fn exact_int(v: Option<&Value>, positive: bool) -> Result<i64, AppError> {
    let val = v.ok_or_else(invalid)?;
    let n = val.as_i64().ok_or_else(invalid)?;
    if positive && n <= 0 {
        return Err(invalid());
    }
    Ok(n)
}

fn non_empty_str<'a>(map: &'a Map<String, Value>, key: &str) -> Result<&'a str, AppError> {
    let s = map.get(key).and_then(|v| v.as_str()).ok_or_else(invalid)?;
    if s.trim().is_empty() {
        return Err(invalid());
    }
    Ok(s)
}

fn valid_origin(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 64
        && s.chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

pub fn validate_deletion(event: &Value) -> Result<(), AppError> {
    let map = event.as_object().ok_or_else(invalid)?;

    let event_id = non_empty_str(map, "eventId")?;
    let parsed_event_id = Uuid::parse_str(event_id).map_err(|_| invalid())?;
    if parsed_event_id.to_string() != event_id {
        return Err(invalid());
    }

    let group_id = non_empty_str(map, "groupId")?;
    let origin = non_empty_str(map, "origin")?;
    if !valid_origin(origin) {
        return Err(invalid());
    }

    let snapshot_json = non_empty_str(map, "snapshotJson")?;
    if snapshot_json.len() > 1024 * 1024 {
        return Err(invalid());
    }

    let _deleted_at = exact_int(map.get("deletedAt"), true)?;
    let principal_id = exact_int(map.get("principalId"), true)?;
    let snap_ver = exact_int(map.get("snapshotVersion"), false)?;
    if snap_ver != 1 {
        return Err(invalid());
    }

    let event_rec_id = match map.get("recognitionId") {
        None | Some(Value::Null) => None,
        Some(Value::String(s)) if !s.trim().is_empty() => Some(s.as_str()),
        Some(_) => return Err(invalid()),
    };

    let snapshot: Value = serde_json::from_str(snapshot_json).map_err(|_| invalid())?;
    let snap_map = snapshot.as_object().ok_or_else(invalid)?;
    let rows = snap_map
        .get("bills")
        .and_then(|v| v.as_array())
        .ok_or_else(invalid)?;
    if rows.is_empty() || rows.len() > 100 {
        return Err(invalid());
    }

    let mut ids = HashSet::with_capacity(rows.len());
    for row in rows {
        let rm = row.as_object().ok_or_else(invalid)?;
        let identity = exact_int(rm.get("id"), true)?;
        if !ids.insert(identity) {
            return Err(invalid());
        }
        exact_int(rm.get("priceCents"), true)?;
        exact_int(rm.get("timeMillis"), false)?;
        exact_int(rm.get("createdAt"), false)?;

        let ty = rm
            .get("type")
            .and_then(|v| v.as_str())
            .ok_or_else(invalid)?;
        if !matches!(ty, "EXPENSE" | "INCOME" | "TRANSFER" | "REFUND") {
            return Err(invalid());
        }

        for k in ["sourcePkg", "strategy", "fingerprint", "assetKey"] {
            if !rm.get(k).map(|v| v.is_string()).unwrap_or(false) {
                return Err(invalid());
            }
        }
        for k in [
            "merchant",
            "method",
            "orderTail",
            "category",
            "subCategory",
            "account",
            "note",
            "transferAccount",
            "productName",
            "productSource",
            "noteOrigin",
            "noteTemplateSnapshot",
        ] {
            if let Some(val) = rm.get(k) {
                if !val.is_null() && !val.is_string() {
                    return Err(invalid());
                }
            }
        }
        for k in [
            "recognitionId",
            "accountId",
            "transferAccountId",
            "feeParentKey",
        ] {
            if let Some(val) = rm.get(k) {
                if !val.is_null() {
                    let s = val.as_str().ok_or_else(invalid)?;
                    if s.trim().is_empty() {
                        return Err(invalid());
                    }
                }
            }
        }
        if !rm
            .get("assetLinked")
            .map(|v| v.is_boolean())
            .unwrap_or(false)
        {
            return Err(invalid());
        }
        let fee = exact_int(rm.get("transferFeeCents"), false)?;
        if fee < 0 {
            return Err(invalid());
        }
    }

    let principal = rows[0].as_object().ok_or_else(invalid)?;
    if exact_int(principal.get("id"), true)? != principal_id {
        return Err(invalid());
    }
    if principal
        .get("feeParentKey")
        .map(|v| !v.is_null())
        .unwrap_or(false)
    {
        return Err(invalid());
    }
    let principal_rec_id = match principal.get("recognitionId") {
        None | Some(Value::Null) => None,
        Some(Value::String(s)) => Some(s.as_str()),
        Some(_) => return Err(invalid()),
    };
    if principal_rec_id != event_rec_id {
        return Err(invalid());
    }

    let principal_asset_key = principal
        .get("assetKey")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let expected_group = if !principal_asset_key.trim().is_empty() {
        principal_asset_key.to_string()
    } else {
        format!("bill:{}", principal_id)
    };
    if group_id != expected_group {
        return Err(invalid());
    }

    let fees = &rows[1..];
    let principal_type = principal.get("type").and_then(|v| v.as_str()).unwrap_or("");
    let principal_fee = exact_int(principal.get("transferFeeCents"), false)?;
    if principal_type != "TRANSFER" {
        if !fees.is_empty() || principal_fee != 0 {
            return Err(invalid());
        }
    } else {
        let expected_count = if principal_fee > 0 { 1 } else { 0 };
        if fees.len() != expected_count {
            return Err(invalid());
        }
        if let Some(fee_val) = fees.first() {
            let fee = fee_val.as_object().ok_or_else(invalid)?;
            if principal_asset_key.trim().is_empty()
                || fee.get("type").and_then(|v| v.as_str()) != Some("EXPENSE")
                || fee.get("feeParentKey").and_then(|v| v.as_str()) != Some(principal_asset_key)
                || exact_int(fee.get("priceCents"), true)? != principal_fee
                || fee.get("accountId").and_then(|v| v.as_str())
                    != principal.get("accountId").and_then(|v| v.as_str())
            {
                return Err(invalid());
            }
        }
    }

    Ok(())
}

pub fn validate_snapshot(path: &Path) -> Result<(i64, DateTime<Utc>, i32), AppError> {
    let file = File::open(path).map_err(|_| invalid())?;
    let reader = BufReader::new(file);
    let data: Value = serde_json::from_reader(reader).map_err(|_| invalid())?;
    let root = data.as_object().ok_or_else(invalid)?;

    if root.get("kind").and_then(|v| v.as_str()) != Some("MyMoneyBackup") {
        return Err(invalid());
    }
    let format = exact_int(root.get("format"), true)?;
    if !matches!(format, 8..=12) {
        return Err(invalid());
    }
    let timestamp = exact_int(root.get("exportedAt"), true)?;
    let snapshot_at = DateTime::from_timestamp_millis(timestamp).ok_or_else(invalid)?;

    let bills = root
        .get("bills")
        .and_then(|v| v.as_array())
        .ok_or_else(invalid)?;

    for bill in bills {
        let bm = bill.as_object().ok_or_else(invalid)?;
        exact_int(bm.get("p"), true)?;
        let ty = bm.get("ty").and_then(|v| v.as_str()).ok_or_else(invalid)?;
        if !matches!(ty, "EXPENSE" | "INCOME" | "TRANSFER" | "REFUND") {
            return Err(invalid());
        }
        exact_int(bm.get("t"), false)?;
        for k in ["fp", "sp", "st"] {
            if !bm.get(k).map(|v| v.is_string()).unwrap_or(false) {
                return Err(invalid());
            }
        }
    }

    if format >= 10 {
        let mut sync_ids = HashSet::new();
        for bill in bills {
            let bm = bill.as_object().ok_or_else(invalid)?;
            let sid = match bm.get("sid") {
                None => "",
                Some(Value::String(s)) => s.as_str(),
                Some(_) => return Err(invalid()),
            };
            if sid.chars().count() > 128 {
                return Err(invalid());
            }
            if !sid.is_empty() && !sync_ids.insert(sid.to_string()) {
                return Err(invalid());
            }
        }
    }

    for k in ["history", "imports"] {
        if let Some(val) = root.get(k) {
            if !val.is_null() {
                let arr = val.as_array().ok_or_else(invalid)?;
                if arr.iter().any(|row| !row.is_object()) {
                    return Err(invalid());
                }
            }
        }
    }
    for k in ["assets", "capture", "ledger", "settings"] {
        if let Some(val) = root.get(k) {
            if !val.is_null() && !val.is_object() {
                return Err(invalid());
            }
        }
    }

    if let Some(deletions_val) = root.get("deletions") {
        if format < 9 {
            return Err(invalid());
        }
        let deletions = deletions_val.as_array().ok_or_else(invalid)?;
        let mut event_ids = HashSet::with_capacity(deletions.len());
        for ev in deletions {
            validate_deletion(ev)?;
            let eid = ev
                .as_object()
                .and_then(|m| m.get("eventId"))
                .and_then(|v| v.as_str())
                .ok_or_else(invalid)?;
            if !event_ids.insert(eid.to_string()) {
                return Err(invalid());
            }
        }
    }

    Ok((bills.len() as i64, snapshot_at, format as i32))
}
