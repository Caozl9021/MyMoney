use std::collections::HashMap;
use std::time::Duration;

use axum::extract::{Path as AxumPath, Query, State};
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use chrono::{DateTime, Days, NaiveDate, Utc};
use serde::Deserialize;
use serde_json::{json, Value};
use sqlx::Row;
use uuid::Uuid;

use crate::analytics::accounting::{local_midnight_millis, parse_tz};
use crate::analytics::queries::{
    build_analytics, build_assets, build_audit, build_diff, build_overview, export_bills_bytes,
    query_bills, BillFilter,
};
use crate::analytics::snapshot::load_snapshot;
use crate::auth::AdminAuth;
use crate::backup::enrollment::{cancel_invitation, invitation_status, issue_connection_code};
use crate::backup::storage::{audit_files, stream_verified_backup_file};
use crate::connect_code::{build_connect_code, render_qr_svg};
use crate::error::{ApiJson, AppError};
use crate::state::AppState;

// ── 既有 /v1/admin/* 兼容接口 ────────────────────────────────────────────

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectCodeRequest {
    #[serde(default)]
    pub server_url: String,
    #[serde(default)]
    pub auth_key: String,
    #[serde(default)]
    pub ctrl_url: String,
    #[serde(default)]
    pub device_name: String,
}

/// 管理台会话（刷新免重输密码）：管理密码换短期随机令牌，前端只持久化该令牌。
pub async fn create_session(
    State(state): State<AppState>,
    auth: AdminAuth,
) -> Result<Json<Value>, AppError> {
    if !auth.is_master() {
        return Err(AppError::forbidden("请使用管理密码创建会话"));
    }
    let (session_token, expires_in_seconds) = state
        .admin_sessions
        .issue(Duration::from_secs(12 * 60 * 60));
    Ok(Json(json!({
        "sessionToken": session_token,
        "expiresInSeconds": expires_in_seconds,
    })))
}

pub async fn logout_session(
    State(state): State<AppState>,
    auth: AdminAuth,
) -> Result<Json<Value>, AppError> {
    if let Some(hash) = auth.session_hash.as_deref() {
        state.admin_sessions.revoke_hash(hash);
    }
    Ok(Json(json!({ "ok": true })))
}

pub async fn backup_settings(State(state): State<AppState>, _auth: AdminAuth) -> Json<Value> {
    Json(json!({
        "serverUrl": state.settings.server_url,
        "ctrlUrl": state.settings.ctrl_url,
        "networkKeyConfigured": !state.settings.tskey.is_empty(),
    }))
}

pub async fn connect_code(
    State(state): State<AppState>,
    _auth: AdminAuth,
    ApiJson(body): ApiJson<ConnectCodeRequest>,
) -> Result<Json<Value>, AppError> {
    if body.server_url.chars().count() > 1024
        || body.auth_key.chars().count() > 512
        || body.ctrl_url.chars().count() > 1024
        || body.device_name.chars().count() > 80
    {
        return Err(AppError::unprocessable("连接码参数长度超限"));
    }

    let server = if !body.server_url.trim().is_empty() {
        body.server_url.trim().to_string()
    } else {
        state.settings.server_url.clone()
    };
    let network_key = if !body.auth_key.trim().is_empty() {
        body.auth_key.trim().to_string()
    } else {
        state.settings.tskey.clone()
    };
    let ctrl = if !body.ctrl_url.trim().is_empty() {
        body.ctrl_url.trim().to_string()
    } else {
        state.settings.ctrl_url.clone()
    };

    // 先验证完整参数，再入库签发业务授权
    build_connect_code(
        &ctrl,
        &network_key,
        &server,
        "validation",
        &body.device_name,
    )
    .map_err(AppError::unprocessable)?;

    let issued = issue_connection_code(&state.pool, 300).await?;
    let code = build_connect_code(
        &ctrl,
        &network_key,
        &server,
        &issued.code,
        &body.device_name,
    )
    .map_err(AppError::unprocessable)?;
    let qr_svg = render_qr_svg(&code);

    Ok(Json(json!({
        "connectCode": code,
        "expiresInSeconds": issued.expires_in_seconds,
        "oneTime": true,
        "invitationId": issued.invitation_id.to_string(),
        "expiresAt": issued.expires_at.to_rfc3339(),
        "serverTime": issued.server_time.to_rfc3339(),
        "qrEncodable": qr_svg.is_some(),
        "qrSvg": qr_svg,
    })))
}

pub async fn connect_code_status(
    State(state): State<AppState>,
    _auth: AdminAuth,
    AxumPath(invitation_id): AxumPath<String>,
) -> Result<Json<Value>, AppError> {
    let target =
        Uuid::parse_str(&invitation_id).map_err(|_| AppError::unprocessable("邀请标识无效"))?;
    Ok(Json(invitation_status(&state.pool, target).await?))
}

pub async fn connect_code_cancel(
    State(state): State<AppState>,
    _auth: AdminAuth,
    AxumPath(invitation_id): AxumPath<String>,
) -> Result<Json<Value>, AppError> {
    let target =
        Uuid::parse_str(&invitation_id).map_err(|_| AppError::unprocessable("邀请标识无效"))?;
    Ok(Json(cancel_invitation(&state.pool, target).await?))
}

pub async fn backup_overview(
    State(state): State<AppState>,
    _auth: AdminAuth,
) -> Result<Json<Value>, AppError> {
    let counts = sqlx::query(
        r#"SELECT
              (SELECT count(*) FROM devices d WHERE d.revoked_at IS NULL) AS active_devices,
              (SELECT count(*) FROM ledger_backups b WHERE b.deleted_at IS NULL) AS live_backups,
              (SELECT coalesce(sum(b.size_bytes),0)::bigint FROM ledger_backups b WHERE b.deleted_at IS NULL) AS live_bytes,
              (SELECT max(b.uploaded_at) FROM ledger_backups b WHERE b.deleted_at IS NULL) AS last_backup_at"#,
    )
    .fetch_one(&state.pool)
    .await?;

    let recent_rows = sqlx::query(
        r#"SELECT b.id, b.device_id, d.name AS device_name, b.bill_count,
                  b.size_bytes, b.uploaded_at, b.snapshot_at, b.app_version, b.backup_schema
           FROM ledger_backups b JOIN devices d ON d.space_id=b.space_id AND d.id=b.device_id
           WHERE b.deleted_at IS NULL ORDER BY b.uploaded_at DESC, b.id DESC LIMIT 10"#,
    )
    .fetch_all(&state.pool)
    .await?;

    let mut recent = Vec::with_capacity(recent_rows.len());
    for r in recent_rows {
        let uploaded_at: DateTime<Utc> = r.try_get("uploaded_at")?;
        let snapshot_at: DateTime<Utc> = r.try_get("snapshot_at")?;
        recent.push(json!({
            "backupId": r.try_get::<Uuid, _>("id")?.to_string(),
            "deviceId": r.try_get::<Uuid, _>("device_id")?.to_string(),
            "deviceName": r.try_get::<String, _>("device_name")?,
            "billCount": r.try_get::<i64, _>("bill_count")?,
            "sizeBytes": r.try_get::<i64, _>("size_bytes")?,
            "uploadedAt": uploaded_at.to_rfc3339(),
            "snapshotAt": snapshot_at.to_rfc3339(),
            "appVersion": r.try_get::<String, _>("app_version")?,
            "backupSchema": r.try_get::<i32, _>("backup_schema")?,
        }));
    }

    let last_backup_at: Option<DateTime<Utc>> = counts.try_get("last_backup_at")?;
    Ok(Json(json!({
        "activeDevices": counts.try_get::<i64, _>("active_devices")?,
        "liveBackups": counts.try_get::<i64, _>("live_backups")?,
        "liveBytes": counts.try_get::<i64, _>("live_bytes")?,
        "lastBackupAt": last_backup_at.map(|t| t.to_rfc3339()),
        "recentBackups": recent,
        "serverTime": Utc::now().to_rfc3339(),
    })))
}

pub async fn backup_sources(
    State(state): State<AppState>,
    _auth: AdminAuth,
) -> Result<Json<Value>, AppError> {
    let rows = sqlx::query(
        r#"SELECT d.id, d.name, d.created_at, d.last_seen_at,
                  d.revoked_at, d.archived_at,
                  (SELECT count(*) FROM ledger_backups b
                   WHERE b.space_id=d.space_id AND b.device_id=d.id
                     AND b.deleted_at IS NULL) AS backup_count,
                  (SELECT max(b.uploaded_at) FROM ledger_backups b
                   WHERE b.space_id=d.space_id AND b.device_id=d.id
                     AND b.deleted_at IS NULL) AS last_backup_at,
                  (SELECT count(*) FROM ledger_changes l
                   WHERE l.space_id=d.space_id AND l.device_id=d.id) AS ledger_change_count,
                  (SELECT count(*) FROM rule_items i
                   WHERE i.space_id=d.space_id AND i.updated_by=d.id) AS rule_count
           FROM devices d ORDER BY d.created_at DESC"#,
    )
    .fetch_all(&state.pool)
    .await?;

    let mut items = Vec::with_capacity(rows.len());
    for row in rows {
        let created_at: DateTime<Utc> = row.try_get("created_at")?;
        let last_seen_at: Option<DateTime<Utc>> = row.try_get("last_seen_at")?;
        let last_backup_at: Option<DateTime<Utc>> = row.try_get("last_backup_at")?;
        let archived_at: Option<DateTime<Utc>> = row.try_get("archived_at")?;
        let revoked_at: Option<DateTime<Utc>> = row.try_get("revoked_at")?;
        items.push(json!({
            "deviceId": row.try_get::<Uuid, _>("id")?.to_string(),
            "name": row.try_get::<String, _>("name")?,
            "createdAt": created_at.to_rfc3339(),
            "lastSeenAt": last_seen_at.map(|t| t.to_rfc3339()),
            "lastBackupAt": last_backup_at.map(|t| t.to_rfc3339()),
            "backupCount": row.try_get::<i64, _>("backup_count")?,
            "ledgerChangeCount": row.try_get::<i64, _>("ledger_change_count")?,
            "ruleCount": row.try_get::<i64, _>("rule_count")?,
            "archived": archived_at.is_some(),
            "revoked": revoked_at.is_some(),
        }));
    }
    Ok(Json(json!({ "items": items })))
}

#[derive(Deserialize)]
pub struct ArchiveRequest {
    pub archived: bool,
}

pub async fn archive_source(
    State(state): State<AppState>,
    _auth: AdminAuth,
    AxumPath(device_id): AxumPath<String>,
    ApiJson(body): ApiJson<ArchiveRequest>,
) -> Result<Json<Value>, AppError> {
    let source =
        Uuid::parse_str(&device_id).map_err(|_| AppError::unprocessable("设备标识无效"))?;
    let row = sqlx::query(
        r#"UPDATE devices SET archived_at=CASE WHEN $1 THEN now() ELSE NULL END
           WHERE id=$2 RETURNING id"#,
    )
    .bind(body.archived)
    .bind(source)
    .fetch_optional(&state.pool)
    .await?;

    if row.is_none() {
        return Err(AppError::not_found("设备不存在"));
    }
    Ok(Json(json!({ "archived": body.archived })))
}

// ── 新增只读财务仪表盘与运维增强 API ─────────────────────────────────────

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapshotsQuery {
    pub device_id: Option<String>,
    #[serde(default)]
    pub offset: i64,
    #[serde(default = "default_page_limit")]
    pub limit: i64,
}

fn default_page_limit() -> i64 {
    50
}

pub async fn admin_backups_list(
    State(state): State<AppState>,
    _auth: AdminAuth,
    Query(query): Query<SnapshotsQuery>,
) -> Result<Json<Value>, AppError> {
    if query.offset < 0 || !(1..=100).contains(&query.limit) {
        return Err(AppError::unprocessable("分页参数无效"));
    }
    let device_uuid = match query.device_id.as_deref() {
        Some(s) if !s.trim().is_empty() => {
            Some(Uuid::parse_str(s.trim()).map_err(|_| AppError::unprocessable("设备标识无效"))?)
        }
        _ => None,
    };

    let rows = match device_uuid {
        Some(did) => {
            sqlx::query(
                r#"SELECT b.id, b.device_id, d.name AS device_name, b.app_version, b.backup_schema,
                          b.sha256, b.size_bytes, b.bill_count, b.snapshot_at, b.uploaded_at
                   FROM ledger_backups b JOIN devices d ON d.space_id=b.space_id AND d.id=b.device_id
                   WHERE b.deleted_at IS NULL AND b.device_id=$1
                   ORDER BY b.uploaded_at DESC, b.id DESC LIMIT $2 OFFSET $3"#,
            )
            .bind(did)
            .bind(query.limit + 1)
            .bind(query.offset)
            .fetch_all(&state.pool)
            .await?
        }
        None => {
            sqlx::query(
                r#"SELECT b.id, b.device_id, d.name AS device_name, b.app_version, b.backup_schema,
                          b.sha256, b.size_bytes, b.bill_count, b.snapshot_at, b.uploaded_at
                   FROM ledger_backups b JOIN devices d ON d.space_id=b.space_id AND d.id=b.device_id
                   WHERE b.deleted_at IS NULL
                   ORDER BY b.uploaded_at DESC, b.id DESC LIMIT $1 OFFSET $2"#,
            )
            .bind(query.limit + 1)
            .bind(query.offset)
            .fetch_all(&state.pool)
            .await?
        }
    };

    let default_row = sqlx::query(
        "SELECT id FROM ledger_backups WHERE deleted_at IS NULL ORDER BY uploaded_at DESC, id DESC LIMIT 1",
    )
    .fetch_optional(&state.pool)
    .await?;
    let default_backup_id: Option<Uuid> = match default_row {
        Some(r) => Some(r.try_get("id")?),
        None => None,
    };

    let has_more = rows.len() as i64 > query.limit;
    let slice_len = rows.len().min(query.limit as usize);
    // 默认（最新）快照的记账周期起始日与实时 Overlay 元数据供 Web 顶栏展示使用；
    // 解析结果走 LRU 缓存，通常无额外开销。
    let default_snapshot = match default_backup_id {
        Some(id) => load_snapshot(
            &state.pool,
            &state.settings.backup_dir,
            &state.snapshot_cache,
            Some(id),
        )
        .await
        .ok(),
        None => None,
    };
    let default_month_start_day = default_snapshot
        .as_ref()
        .map(|s| s.month_start_day)
        .unwrap_or(1);
    // 每台设备的最新快照才可能叠加增量；用两条聚合查询一次取回所有账本的状态。
    let latest_ids: HashMap<Uuid, ()> = sqlx::query(
        r#"SELECT DISTINCT ON (device_id) id FROM ledger_backups
           WHERE deleted_at IS NULL
           ORDER BY device_id, uploaded_at DESC, id DESC"#,
    )
    .fetch_all(&state.pool)
    .await?
    .iter()
    .filter_map(|r| r.try_get::<Uuid, _>("id").ok())
    .map(|id| (id, ()))
    .collect();
    let mut overlay_revisions: HashMap<Uuid, i64> = HashMap::new();
    let mut overlay_updated: HashMap<Uuid, chrono::DateTime<Utc>> = HashMap::new();
    for row in sqlx::query(
        r#"SELECT ledger_id, COALESCE(MAX(revision),0) AS rev,
                  MAX(content_updated_at) AS content_ts, MAX(deleted_at) AS deleted_ts
           FROM ledger_items GROUP BY ledger_id"#,
    )
    .fetch_all(&state.pool)
    .await?
    {
        let ledger_id: Uuid = row.try_get("ledger_id")?;
        overlay_revisions.insert(ledger_id, row.try_get("rev")?);
        let content_dt = row
            .try_get::<Option<i64>, _>("content_ts")?
            .and_then(chrono::DateTime::<Utc>::from_timestamp_millis);
        let deleted_dt: Option<DateTime<Utc>> = row.try_get("deleted_ts")?;
        if let Some(ts) = match (content_dt, deleted_dt) {
            (Some(a), Some(b)) => Some(a.max(b)),
            (Some(a), None) => Some(a),
            (None, Some(b)) => Some(b),
            (None, None) => None,
        } {
            overlay_updated.insert(ledger_id, ts);
        }
    }
    let mut items = Vec::with_capacity(slice_len);
    for r in &rows[..slice_len] {
        let sha: String = r.try_get("sha256")?;
        let snap_at: DateTime<Utc> = r.try_get("snapshot_at")?;
        let up_at: DateTime<Utc> = r.try_get("uploaded_at")?;
        let id: Uuid = r.try_get("id")?;
        let device_id: Uuid = r.try_get("device_id")?;
        let is_device_latest = latest_ids.contains_key(&id);
        let mut item = json!({
            "backupId": id.to_string(),
            "deviceId": device_id.to_string(),
            "deviceName": r.try_get::<String, _>("device_name")?,
            "appVersion": r.try_get::<String, _>("app_version")?,
            "backupSchema": r.try_get::<i32, _>("backup_schema")?,
            "sha256": sha.trim(),
            "sizeBytes": r.try_get::<i64, _>("size_bytes")?,
            "billCount": r.try_get::<i64, _>("bill_count")?,
            "snapshotAt": snap_at.to_rfc3339(),
            "uploadedAt": up_at.to_rfc3339(),
            "overlayRevision": if is_device_latest {
                *overlay_revisions.get(&device_id).unwrap_or(&0)
            } else {
                0
            },
            "overlayUpdatedAt": if is_device_latest {
                overlay_updated.get(&device_id).map(|t| t.to_rfc3339())
            } else {
                None
            },
        });
        if Some(id) == default_backup_id {
            item["monthStartDay"] = json!(default_month_start_day);
            if let Some(snap) = default_snapshot.as_ref() {
                // 默认最新快照展示叠加后的实时账单数，而不是磁盘基准值。
                item["billCount"] = json!(snap.meta.bill_count);
                item["overlayRevision"] = json!(snap.meta.overlay_revision);
                item["overlayUpdatedAt"] =
                    json!(snap.meta.overlay_updated_at.map(|t| t.to_rfc3339()));
            }
        }
        items.push(item);
    }

    Ok(Json(json!({
        "defaultBackupId": default_backup_id.map(|u| u.to_string()),
        "items": items,
        "hasMore": has_more,
        "nextOffset": query.offset + slice_len as i64,
    })))
}

pub async fn admin_backup_download(
    State(state): State<AppState>,
    _auth: AdminAuth,
    AxumPath(backup_id): AxumPath<String>,
) -> Result<Response, AppError> {
    let bid = Uuid::parse_str(&backup_id).map_err(|_| AppError::unprocessable("备份标识无效"))?;
    let row = sqlx::query(
        "SELECT storage_key, size_bytes, sha256 FROM ledger_backups WHERE id=$1 AND deleted_at IS NULL LIMIT 1",
    )
    .bind(bid)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::not_found("备份不存在"))?;

    let storage_key: String = row.try_get("storage_key")?;
    let size_bytes: i64 = row.try_get("size_bytes")?;
    let sha256: String = row.try_get("sha256")?;
    stream_verified_backup_file(
        &state.settings.backup_dir,
        bid,
        &storage_key,
        size_bytes,
        &sha256,
    )
    .await
}

pub async fn admin_storage_audit(
    State(state): State<AppState>,
    _auth: AdminAuth,
) -> Result<Json<Value>, AppError> {
    Ok(Json(
        audit_files(&state.pool, &state.settings.backup_dir).await?,
    ))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PeriodAnalyticsQuery {
    pub backup_id: Option<String>,
    #[serde(default = "default_period_mode")]
    pub period_mode: String,
    pub start: Option<String>,
    pub end: Option<String>,
    pub category: Option<String>,
    pub tz: Option<String>,
}

fn default_period_mode() -> String {
    "ledger".to_string()
}

fn parse_optional_uuid(raw: Option<&str>, msg: &str) -> Result<Option<Uuid>, AppError> {
    match raw {
        Some(s) if !s.trim().is_empty() => Ok(Some(
            Uuid::parse_str(s.trim()).map_err(|_| AppError::unprocessable(msg))?,
        )),
        _ => Ok(None),
    }
}

pub async fn dashboard_overview(
    State(state): State<AppState>,
    _auth: AdminAuth,
    Query(query): Query<PeriodAnalyticsQuery>,
) -> Result<Json<Value>, AppError> {
    let bid = parse_optional_uuid(query.backup_id.as_deref(), "备份标识无效")?;
    let snap = load_snapshot(
        &state.pool,
        &state.settings.backup_dir,
        &state.snapshot_cache,
        bid,
    )
    .await?;
    let out = build_overview(
        &snap,
        &query.period_mode,
        query.start.as_deref(),
        query.end.as_deref(),
        query.tz.as_deref(),
    )?;
    Ok(Json(out))
}

pub async fn dashboard_analytics(
    State(state): State<AppState>,
    _auth: AdminAuth,
    Query(query): Query<PeriodAnalyticsQuery>,
) -> Result<Json<Value>, AppError> {
    let bid = parse_optional_uuid(query.backup_id.as_deref(), "备份标识无效")?;
    let snap = load_snapshot(
        &state.pool,
        &state.settings.backup_dir,
        &state.snapshot_cache,
        bid,
    )
    .await?;
    let out = build_analytics(
        &snap,
        &query.period_mode,
        query.start.as_deref(),
        query.end.as_deref(),
        query.category.as_deref(),
        query.tz.as_deref(),
    )?;
    Ok(Json(out))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BillsQueryParams {
    pub backup_id: Option<String>,
    pub q: Option<String>,
    #[serde(rename = "type")]
    pub bill_type: Option<String>,
    pub category: Option<String>,
    pub sub_category: Option<String>,
    pub account: Option<String>,
    pub reimburse_status: Option<String>,
    pub strategy: Option<String>,
    pub channel: Option<String>,
    pub min_cents: Option<i64>,
    pub max_cents: Option<i64>,
    pub start: Option<String>,
    pub end: Option<String>,
    #[serde(default = "default_sort")]
    pub sort: String,
    #[serde(default = "default_page")]
    pub page: usize,
    #[serde(default = "default_page_size")]
    pub page_size: usize,
    pub tz: Option<String>,
    #[serde(default = "default_export_format")]
    pub format: String,
}

fn default_sort() -> String {
    "time_desc".to_string()
}

fn default_page() -> usize {
    1
}

fn default_page_size() -> usize {
    50
}

fn default_export_format() -> String {
    "csv".to_string()
}

fn build_bill_filter(q: &BillsQueryParams, tz: chrono_tz::Tz) -> Result<BillFilter, AppError> {
    let from_ms = match q.start.as_deref() {
        Some(s) if !s.trim().is_empty() => {
            let sd = NaiveDate::parse_from_str(s.trim(), "%Y-%m-%d")
                .map_err(|_| AppError::unprocessable("开始日期格式无效"))?;
            Some(local_midnight_millis(sd, tz))
        }
        _ => None,
    };
    let to_ms = match q.end.as_deref() {
        Some(e) if !e.trim().is_empty() => {
            let ed = NaiveDate::parse_from_str(e.trim(), "%Y-%m-%d")
                .map_err(|_| AppError::unprocessable("结束日期格式无效"))?;
            let excl = ed.checked_add_days(Days::new(1)).unwrap();
            Some(local_midnight_millis(excl, tz))
        }
        _ => None,
    };
    Ok(BillFilter {
        q: q.q.clone(),
        bill_type: q.bill_type.clone(),
        category: q.category.clone(),
        sub_category: q.sub_category.clone(),
        account: q.account.clone(),
        reimburse_status: q.reimburse_status.clone(),
        strategy: q.strategy.clone(),
        channel: q.channel.clone(),
        min_cents: q.min_cents,
        max_cents: q.max_cents,
        from_ms,
        to_ms,
        sort: q.sort.clone(),
        page: q.page,
        page_size: q.page_size,
    })
}

pub async fn dashboard_bills(
    State(state): State<AppState>,
    _auth: AdminAuth,
    Query(query): Query<BillsQueryParams>,
) -> Result<Json<Value>, AppError> {
    let tz = parse_tz(query.tz.as_deref())?;
    let bid = parse_optional_uuid(query.backup_id.as_deref(), "备份标识无效")?;
    let snap = load_snapshot(
        &state.pool,
        &state.settings.backup_dir,
        &state.snapshot_cache,
        bid,
    )
    .await?;
    let filter = build_bill_filter(&query, tz)?;
    Ok(Json(query_bills(&snap, &filter, tz)))
}

pub async fn dashboard_bills_export(
    State(state): State<AppState>,
    _auth: AdminAuth,
    Query(query): Query<BillsQueryParams>,
) -> Result<Response, AppError> {
    let tz = parse_tz(query.tz.as_deref())?;
    let bid = parse_optional_uuid(query.backup_id.as_deref(), "备份标识无效")?;
    let snap = load_snapshot(
        &state.pool,
        &state.settings.backup_dir,
        &state.snapshot_cache,
        bid,
    )
    .await?;
    let filter = build_bill_filter(&query, tz)?;
    let (bytes, ctype, filename) = export_bills_bytes(&snap, &filter, &query.format, tz)?;
    let mut resp = Response::new(axum::body::Body::from(bytes));
    *resp.status_mut() = StatusCode::OK;
    resp.headers_mut()
        .insert(header::CONTENT_TYPE, HeaderValue::from_static(ctype));
    if let Ok(disp) = HeaderValue::from_str(&format!("attachment; filename=\"{}\"", filename)) {
        resp.headers_mut().insert(header::CONTENT_DISPOSITION, disp);
    }
    resp.headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    Ok(resp)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AssetsQueryParams {
    pub backup_id: Option<String>,
    pub start: Option<String>,
    pub end: Option<String>,
    pub account_id: Option<String>,
    pub tz: Option<String>,
}

pub async fn dashboard_assets(
    State(state): State<AppState>,
    _auth: AdminAuth,
    Query(query): Query<AssetsQueryParams>,
) -> Result<Json<Value>, AppError> {
    let tz = parse_tz(query.tz.as_deref())?;
    let bid = parse_optional_uuid(query.backup_id.as_deref(), "备份标识无效")?;
    let snap = load_snapshot(
        &state.pool,
        &state.settings.backup_dir,
        &state.snapshot_cache,
        bid,
    )
    .await?;
    Ok(Json(build_assets(
        &snap,
        query.start.as_deref(),
        query.end.as_deref(),
        query.account_id.as_deref(),
        tz,
    )?))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuditQueryParams {
    pub backup_id: Option<String>,
    pub tz: Option<String>,
}

pub async fn dashboard_audit(
    State(state): State<AppState>,
    _auth: AdminAuth,
    Query(query): Query<AuditQueryParams>,
) -> Result<Json<Value>, AppError> {
    let tz = parse_tz(query.tz.as_deref())?;
    let bid = parse_optional_uuid(query.backup_id.as_deref(), "备份标识无效")?;
    let snap = load_snapshot(
        &state.pool,
        &state.settings.backup_dir,
        &state.snapshot_cache,
        bid,
    )
    .await?;
    Ok(Json(build_audit(&snap, tz)))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiffQueryParams {
    pub left_backup_id: String,
    pub right_backup_id: String,
    pub tz: Option<String>,
}

pub async fn dashboard_diff(
    State(state): State<AppState>,
    _auth: AdminAuth,
    Query(query): Query<DiffQueryParams>,
) -> Result<Json<Value>, AppError> {
    let tz = parse_tz(query.tz.as_deref())?;
    let left_id = Uuid::parse_str(query.left_backup_id.trim())
        .map_err(|_| AppError::unprocessable("左侧快照标识无效"))?;
    let right_id = Uuid::parse_str(query.right_backup_id.trim())
        .map_err(|_| AppError::unprocessable("右侧快照标识无效"))?;

    let left = load_snapshot(
        &state.pool,
        &state.settings.backup_dir,
        &state.snapshot_cache,
        Some(left_id),
    )
    .await?;
    let right = load_snapshot(
        &state.pool,
        &state.settings.backup_dir,
        &state.snapshot_cache,
        Some(right_id),
    )
    .await?;
    Ok(Json(build_diff(&left, &right, tz)))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncHeadQuery {
    pub backup_id: Option<String>,
}

/// 极简实时探针：Web 前台每 15 秒问一次，只回 < 300 B 的版本信息。
pub async fn dashboard_sync_head(
    State(state): State<AppState>,
    _auth: AdminAuth,
    Query(query): Query<SyncHeadQuery>,
) -> Result<Json<Value>, AppError> {
    let bid = parse_optional_uuid(query.backup_id.as_deref(), "备份标识无效")?;
    let snap = load_snapshot(
        &state.pool,
        &state.settings.backup_dir,
        &state.snapshot_cache,
        bid,
    )
    .await?;
    Ok(Json(json!({
        "backupId": snap.meta.backup_id.to_string(),
        "deviceId": snap.meta.device_id.to_string(),
        "deviceName": snap.meta.device_name,
        "snapshotAt": snap.meta.snapshot_at.to_rfc3339(),
        "overlayRevision": snap.meta.overlay_revision,
        "overlayUpdatedAt": snap.meta.overlay_updated_at.map(|t| t.to_rfc3339()),
        "billCount": snap.meta.bill_count,
        "monthStartDay": snap.month_start_day,
    })))
}

#[allow(dead_code)]
fn _keep_into_response<T: IntoResponse>(x: T) -> Response {
    x.into_response()
}
