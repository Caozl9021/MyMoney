use std::collections::HashMap;

use axum::extract::{Query, State};
use axum::http::HeaderMap;
use axum::Json;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sqlx::{Postgres, Row, Transaction};
use uuid::Uuid;

use crate::auth::{constant_time_eq, hash_token, new_token, CurrentDevice};
use crate::error::{python_json_dumps_sorted_compact, ApiJson, AppError};
use crate::state::AppState;

pub async fn ensure_space<'e, E>(executor: E) -> Result<(Uuid, Uuid, i64), AppError>
where
    E: sqlx::Executor<'e, Database = Postgres>,
{
    let row = sqlx::query(
        r#"WITH existing AS (
               SELECT id, epoch, current_revision FROM sync_spaces ORDER BY created_at, id LIMIT 1
           ), inserted AS (
               INSERT INTO sync_spaces(id, epoch, name, current_revision)
               SELECT $1, $2, $3, 0
               WHERE NOT EXISTS (SELECT 1 FROM existing)
               RETURNING id, epoch, current_revision
           )
           SELECT id, epoch, current_revision FROM existing
           UNION ALL
           SELECT id, epoch, current_revision FROM inserted
           LIMIT 1"#,
    )
    .bind(Uuid::new_v4())
    .bind(Uuid::new_v4())
    .bind("家庭账本")
    .fetch_one(executor)
    .await?;

    Ok((
        row.try_get("id")?,
        row.try_get("epoch")?,
        row.try_get("current_revision")?,
    ))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairRequest {
    pub code: String,
    pub device_name: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairResponse {
    pub protocol_version: i32,
    pub space_id: Uuid,
    pub epoch: Uuid,
    pub device_id: Uuid,
    pub token: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PushItem {
    pub operation_id: String,
    pub epoch: Uuid,
    pub item_id: String,
    pub base_revision: i64,
    pub operation: String,
    pub kind: String,
    #[serde(default = "default_schema_version")]
    pub schema_version: i32,
    pub content_updated_at: Option<i64>,
    pub sort_key: Option<String>,
    pub natural_key: Option<String>,
    pub payload: Option<Value>,
}

fn default_schema_version() -> i32 {
    1
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PushRequest {
    #[serde(default = "default_schema_version")]
    pub protocol_version: i32,
    pub space_id: Uuid,
    pub epoch: Uuid,
    pub items: Vec<PushItem>,
}

#[derive(Deserialize)]
pub struct PullQuery {
    #[serde(default)]
    pub after: i64,
    #[serde(default = "default_pull_limit")]
    pub limit: i64,
}

fn default_pull_limit() -> i64 {
    100
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AckRequest {
    pub through_revision: i64,
    pub epoch: Uuid,
}

pub async fn health(State(state): State<AppState>) -> Result<Json<Value>, AppError> {
    sqlx::query("SELECT 1").execute(&state.pool).await?;
    Ok(Json(json!({
        "ok": true,
        "protocolVersion": state.settings.protocol_version
    })))
}

pub async fn admin_pair_code(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Value>, AppError> {
    let provided = headers
        .get("x-admin-token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if state.settings.admin_token.is_empty()
        || !constant_time_eq(&state.settings.admin_token, provided)
    {
        return Err(AppError::forbidden("管理令牌无效"));
    }
    let code = state.pairing.issue(None);
    let expires = if state.settings.pairing_ttl_seconds <= 0 {
        0
    } else {
        state.settings.pairing_ttl_seconds
    };
    Ok(Json(json!({ "code": code, "expiresInSeconds": expires })))
}

pub async fn pair(
    State(state): State<AppState>,
    ApiJson(body): ApiJson<PairRequest>,
) -> Result<Json<PairResponse>, AppError> {
    if body.code.is_empty() || body.code.chars().count() > 32 {
        return Err(AppError::unprocessable("配对码格式无效"));
    }
    if body.device_name.is_empty() || body.device_name.chars().count() > 80 {
        return Err(AppError::unprocessable("设备名称格式无效"));
    }
    if !state.pairing.verify(&body.code) {
        return Err(AppError::unauthorized("配对码无效或已过期"));
    }
    let mut tx = state.pool.begin().await?;
    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
        .bind("device-enrollment-ensure-space")
        .execute(&mut *tx)
        .await?;
    let (space_id, epoch, _) = ensure_space(&mut *tx).await?;
    let device_id = Uuid::new_v4();
    let token = new_token();
    let token_hash = hash_token(&token);
    let device_name: String = body.device_name.chars().take(80).collect();

    sqlx::query(
        r#"INSERT INTO devices(space_id, id, name, token_hash)
           VALUES ($1, $2, $3, $4)"#,
    )
    .bind(space_id)
    .bind(device_id)
    .bind(&device_name)
    .bind(&token_hash)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;

    Ok(Json(PairResponse {
        protocol_version: state.settings.protocol_version,
        space_id,
        epoch,
        device_id,
        token,
    }))
}

fn normalize_merchant(value: &str) -> String {
    value
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

fn validate_and_key(item: &PushItem) -> Result<Option<String>, AppError> {
    if item.operation_id.len() < 16 || item.operation_id.len() > 96 {
        return Err(AppError::unprocessable("操作编号长度无效"));
    }
    if item.item_id.is_empty() || item.item_id.len() > 128 {
        return Err(AppError::unprocessable("条目编号无效"));
    }
    if item.base_revision < 0 {
        return Err(AppError::unprocessable("基础修订号不能为负数"));
    }
    if !matches!(item.operation.as_str(), "put" | "delete" | "restore") {
        return Err(AppError::unprocessable("操作类型无效"));
    }
    if !matches!(
        item.kind.as_str(),
        "recognition_rule" | "merchant_note_rule" | "official_rule_switch" | "note_defaults"
    ) {
        return Err(AppError::unprocessable("未知规则类型"));
    }
    if item.schema_version != 1 {
        return Err(AppError::unprocessable("不支持的规则格式版本"));
    }
    if let Some(ref sk) = item.sort_key {
        if sk.chars().count() > 80 {
            return Err(AppError::unprocessable("排序键过长"));
        }
    }
    if let Some(ref nk) = item.natural_key {
        if nk.chars().count() > 160 {
            return Err(AppError::unprocessable("自然键过长"));
        }
    }
    if item.operation == "delete" {
        return Ok(None);
    }
    let Some(Value::Object(ref map)) = item.payload else {
        return Err(AppError::unprocessable("规则内容为空"));
    };

    match item.kind.as_str() {
        "recognition_rule" => {
            if map.get("id").and_then(|v| v.as_str()) != Some(item.item_id.as_str()) {
                return Err(AppError::unprocessable("个人规则标识不匹配"));
            }
            Ok(Some(item.item_id.clone()))
        }
        "merchant_note_rule" => {
            let app = map.get("app").and_then(|v| v.as_str()).unwrap_or("");
            let kind = map.get("type").and_then(|v| v.as_str()).unwrap_or("");
            let merchant = map.get("merchant").and_then(|v| v.as_str()).unwrap_or("");
            if app.is_empty() || kind.is_empty() || merchant.is_empty() {
                return Err(AppError::unprocessable(
                    "商家备注规则缺少 app/type/merchant",
                ));
            }
            let mode = map.get("mode").and_then(|v| v.as_str()).unwrap_or("");
            if !matches!(mode, "PRODUCT" | "FIXED" | "NONE") {
                return Err(AppError::unprocessable("商家备注模式无效"));
            }
            Ok(Some(format!(
                "{}|{}|{}",
                app,
                kind,
                normalize_merchant(merchant)
            )))
        }
        "official_rule_switch" => {
            if !map.get("enabled").map(|v| v.is_boolean()).unwrap_or(false) {
                return Err(AppError::unprocessable("官方规则开关无效"));
            }
            let nk = item.natural_key.as_deref().unwrap_or("");
            if nk.is_empty() || nk.len() > 128 {
                return Err(AppError::unprocessable("官方规则开关缺少规则标识"));
            }
            Ok(Some(nk.to_string()))
        }
        "note_defaults" => {
            if !map
                .get("productFirst")
                .map(|v| v.is_boolean())
                .unwrap_or(false)
            {
                return Err(AppError::unprocessable("备注默认设置无效"));
            }
            let tmpl = map
                .get("noteTemplate")
                .and_then(|v| v.as_str())
                .ok_or_else(|| AppError::unprocessable("备注模板无效"))?;
            if tmpl.chars().count() > 200 {
                return Err(AppError::unprocessable("备注模板无效"));
            }
            Ok(Some("defaults".to_string()))
        }
        _ => Err(AppError::unprocessable("未知规则类型")),
    }
}

fn snapshot_row(row: &sqlx::postgres::PgRow) -> Result<Value, AppError> {
    let deleted_at: Option<DateTime<Utc>> = row.try_get("deleted_at")?;
    Ok(json!({
        "itemId": row.try_get::<String, _>("id")?,
        "kind": row.try_get::<String, _>("kind")?,
        "schemaVersion": row.try_get::<i32, _>("schema_version")?,
        "payload": row.try_get::<Value, _>("payload")?,
        "naturalKey": row.try_get::<Option<String>, _>("natural_key")?,
        "sortKey": row.try_get::<String, _>("sort_key")?,
        "revision": row.try_get::<i64, _>("revision")?,
        "contentUpdatedAt": row.try_get::<Option<i64>, _>("content_updated_at")?,
        "deletedAt": deleted_at.map(|d| d.to_rfc3339()),
    }))
}

fn push_item_hash(item: &PushItem) -> String {
    let val = json!({
        "operationId": item.operation_id,
        "epoch": item.epoch.to_string(),
        "itemId": item.item_id,
        "baseRevision": item.base_revision,
        "operation": item.operation,
        "kind": item.kind,
        "schemaVersion": item.schema_version,
        "contentUpdatedAt": item.content_updated_at,
        "sortKey": item.sort_key,
        "naturalKey": item.natural_key,
        "payload": item.payload,
    });
    crate::auth::sha256_bytes(python_json_dumps_sorted_compact(&val).as_bytes())
}

async fn record_sync_result(
    tx: &mut Transaction<'_, Postgres>,
    space_id: Uuid,
    device_id: Uuid,
    item: &PushItem,
    request_hash: &str,
    result: &Value,
) -> Result<(), AppError> {
    sqlx::query(
        r#"INSERT INTO sync_operations(space_id, device_id, operation_id, request_hash, item_id, base_revision, result)
           VALUES ($1,$2,$3,$4,$5,$6,$7)"#,
    )
    .bind(space_id)
    .bind(device_id)
    .bind(&item.operation_id)
    .bind(request_hash)
    .bind(&item.item_id)
    .bind(item.base_revision)
    .bind(result)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

pub async fn sync_snapshot(
    State(state): State<AppState>,
    device: CurrentDevice,
) -> Result<Json<Value>, AppError> {
    let space = sqlx::query("SELECT id, epoch, current_revision FROM sync_spaces WHERE id=$1")
        .bind(device.space_id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::conflict("同步空间不存在"))?;

    let limit = state.settings.max_snapshot_items;
    let rows = sqlx::query(
        "SELECT * FROM rule_items WHERE space_id=$1 ORDER BY kind, sort_key, id LIMIT $2",
    )
    .bind(device.space_id)
    .bind(limit + 1)
    .fetch_all(&state.pool)
    .await?;

    if rows.len() as i64 > limit {
        return Err(AppError::payload_too_large(
            "规则快照超过上限，请先在 App 内整理",
        ));
    }

    let mut items = Vec::with_capacity(rows.len());
    for r in &rows {
        items.push(snapshot_row(r)?);
    }

    Ok(Json(json!({
        "protocolVersion": state.settings.protocol_version,
        "spaceId": space.try_get::<Uuid, _>("id")?.to_string(),
        "epoch": space.try_get::<Uuid, _>("epoch")?.to_string(),
        "serverRevision": space.try_get::<i64, _>("current_revision")?,
        "items": items,
    })))
}

pub async fn sync_push(
    State(state): State<AppState>,
    device: CurrentDevice,
    headers: HeaderMap,
    ApiJson(body): ApiJson<PushRequest>,
) -> Result<Json<Value>, AppError> {
    if let Some(len_hdr) = headers
        .get(axum::http::header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
    {
        if let Ok(len) = len_hdr.parse::<usize>() {
            if len > state.settings.max_push_bytes {
                return Err(AppError::payload_too_large("同步请求超过大小限制"));
            }
        }
    }
    if body.protocol_version != state.settings.protocol_version {
        return Err(AppError::conflict("协议版本不兼容"));
    }
    if body.space_id != device.space_id {
        return Err(AppError::forbidden("不能写入其他同步空间"));
    }
    if body.items.is_empty() || body.items.len() > state.settings.max_push_items {
        return Err(AppError::unprocessable("同步批次条目数无效"));
    }

    let space_row = sqlx::query("SELECT id, epoch, current_revision FROM sync_spaces WHERE id=$1")
        .bind(body.space_id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::conflict("同步空间不存在"))?;

    let space_epoch: Uuid = space_row.try_get("epoch")?;

    let mut results = Vec::with_capacity(body.items.len());
    for item in &body.items {
        if item.epoch != space_epoch {
            return Err(AppError::conflict("同步空间已重置，请重新对账"));
        }
        let natural_key = validate_and_key(item)?;
        let req_hash = push_item_hash(item);

        let mut tx = state.pool.begin().await?;
        let previous = sqlx::query(
            r#"SELECT request_hash, result FROM sync_operations
               WHERE space_id=$1 AND device_id=$2 AND operation_id=$3"#,
        )
        .bind(device.space_id)
        .bind(device.device_id)
        .bind(&item.operation_id)
        .fetch_optional(&mut *tx)
        .await?;

        if let Some(prev) = previous {
            let prev_hash: String = prev.try_get("request_hash")?;
            if prev_hash.trim() != req_hash {
                return Err(AppError::conflict("操作编号已用于不同内容"));
            }
            results.push(prev.try_get::<Value, _>("result")?);
            tx.commit().await?;
            continue;
        }

        let current =
            sqlx::query("SELECT * FROM rule_items WHERE space_id=$1 AND id=$2 FOR UPDATE")
                .bind(device.space_id)
                .bind(&item.item_id)
                .fetch_optional(&mut *tx)
                .await?;

        let current_revision: i64 = match current {
            Some(ref r) => r.try_get("revision")?,
            None => 0,
        };

        if item.base_revision != current_revision {
            let remote = match current {
                Some(ref r) => Some(snapshot_row(r)?),
                None => None,
            };
            let result = json!({
                "status": "conflict",
                "operationId": item.operation_id,
                "itemId": item.item_id,
                "baseRevision": item.base_revision,
                "remoteRevision": current_revision,
                "remote": remote,
            });
            record_sync_result(
                &mut tx,
                device.space_id,
                device.device_id,
                item,
                &req_hash,
                &result,
            )
            .await?;
            tx.commit().await?;
            results.push(result);
            continue;
        }

        let (payload, final_nk, deleted_at): (Value, Option<String>, Option<DateTime<Utc>>) =
            if item.operation == "delete" {
                let Some(ref r) = current else {
                    let result = json!({
                        "status": "conflict",
                        "operationId": item.operation_id,
                        "itemId": item.item_id,
                        "baseRevision": item.base_revision,
                        "remoteRevision": current_revision,
                        "remote": null,
                    });
                    record_sync_result(
                        &mut tx,
                        device.space_id,
                        device.device_id,
                        item,
                        &req_hash,
                        &result,
                    )
                    .await?;
                    tx.commit().await?;
                    results.push(result);
                    continue;
                };
                let prev_del: Option<DateTime<Utc>> = r.try_get("deleted_at")?;
                if prev_del.is_some() {
                    let result = json!({
                        "status": "conflict",
                        "operationId": item.operation_id,
                        "itemId": item.item_id,
                        "baseRevision": item.base_revision,
                        "remoteRevision": current_revision,
                        "remote": snapshot_row(r)?,
                    });
                    record_sync_result(
                        &mut tx,
                        device.space_id,
                        device.device_id,
                        item,
                        &req_hash,
                        &result,
                    )
                    .await?;
                    tx.commit().await?;
                    results.push(result);
                    continue;
                }
                (
                    r.try_get("payload")?,
                    r.try_get("natural_key")?,
                    Some(Utc::now()),
                )
            } else {
                if let Some(ref r) = current {
                    let prev_del: Option<DateTime<Utc>> = r.try_get("deleted_at")?;
                    if prev_del.is_some() && item.operation != "restore" {
                        let result = json!({
                            "status": "conflict",
                            "operationId": item.operation_id,
                            "itemId": item.item_id,
                            "baseRevision": item.base_revision,
                            "remoteRevision": current_revision,
                            "remote": snapshot_row(r)?,
                            "error": "remote_deleted",
                        });
                        record_sync_result(
                            &mut tx,
                            device.space_id,
                            device.device_id,
                            item,
                            &req_hash,
                            &result,
                        )
                        .await?;
                        tx.commit().await?;
                        results.push(result);
                        continue;
                    }
                }
                (
                    item.payload.clone().unwrap_or(Value::Null),
                    natural_key,
                    None,
                )
            };

        let locked_space =
            sqlx::query("SELECT current_revision FROM sync_spaces WHERE id=$1 FOR UPDATE")
                .bind(device.space_id)
                .fetch_one(&mut *tx)
                .await?;
        let current_space_rev: i64 = locked_space.try_get("current_revision")?;
        let next_revision = current_space_rev + 1;
        let sort_key = item.sort_key.as_deref().unwrap_or("");

        let saved_row = if current.is_none() {
            sqlx::query(
                r#"INSERT INTO rule_items(space_id, id, kind, schema_version, payload, natural_key,
                       sort_key, revision, content_updated_at, updated_by, deleted_at)
                   VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) RETURNING *"#,
            )
            .bind(device.space_id)
            .bind(&item.item_id)
            .bind(&item.kind)
            .bind(item.schema_version)
            .bind(&payload)
            .bind(&final_nk)
            .bind(sort_key)
            .bind(next_revision)
            .bind(item.content_updated_at)
            .bind(device.device_id)
            .bind(deleted_at)
            .fetch_one(&mut *tx)
            .await?
        } else {
            sqlx::query(
                r#"UPDATE rule_items SET kind=$1, schema_version=$2, payload=$3, natural_key=$4,
                       sort_key=$5, revision=$6, content_updated_at=$7, updated_by=$8, deleted_at=$9
                   WHERE space_id=$10 AND id=$11 RETURNING *"#,
            )
            .bind(&item.kind)
            .bind(item.schema_version)
            .bind(&payload)
            .bind(&final_nk)
            .bind(sort_key)
            .bind(next_revision)
            .bind(item.content_updated_at)
            .bind(device.device_id)
            .bind(deleted_at)
            .bind(device.space_id)
            .bind(&item.item_id)
            .fetch_one(&mut *tx)
            .await?
        };

        sqlx::query("UPDATE sync_spaces SET current_revision=$1 WHERE id=$2")
            .bind(next_revision)
            .bind(device.space_id)
            .execute(&mut *tx)
            .await?;

        let snap = snapshot_row(&saved_row)?;
        sqlx::query(
            r#"INSERT INTO sync_changes(space_id, revision, item_id, operation, snapshot, device_id)
               VALUES ($1,$2,$3,$4,$5,$6)"#,
        )
        .bind(device.space_id)
        .bind(next_revision)
        .bind(&item.item_id)
        .bind(&item.operation)
        .bind(&snap)
        .bind(device.device_id)
        .execute(&mut *tx)
        .await?;

        let result = json!({
            "status": "ok",
            "operationId": item.operation_id,
            "itemId": item.item_id,
            "revision": next_revision,
            "snapshot": snap,
        });
        record_sync_result(
            &mut tx,
            device.space_id,
            device.device_id,
            item,
            &req_hash,
            &result,
        )
        .await?;
        tx.commit().await?;
        results.push(result);
    }

    Ok(Json(json!({
        "protocolVersion": state.settings.protocol_version,
        "items": results,
    })))
}

pub async fn sync_pull(
    State(state): State<AppState>,
    device: CurrentDevice,
    Query(query): Query<PullQuery>,
) -> Result<Json<Value>, AppError> {
    if query.after < 0 {
        return Err(AppError::unprocessable("after 游标不能为负数"));
    }
    if query.limit < 1 || query.limit > 100 {
        return Err(AppError::unprocessable("limit 必须在 1 到 100 之间"));
    }
    let space = sqlx::query("SELECT id, epoch, current_revision FROM sync_spaces WHERE id=$1")
        .bind(device.space_id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::conflict("同步空间不存在"))?;

    let limit = query.limit.clamp(1, state.settings.max_pull_items);
    let rows = sqlx::query(
        r#"SELECT revision, item_id, operation, snapshot, device_id, recorded_at
           FROM sync_changes WHERE space_id=$1 AND revision > $2
           ORDER BY revision LIMIT $3"#,
    )
    .bind(device.space_id)
    .bind(query.after)
    .bind(limit + 1)
    .fetch_all(&state.pool)
    .await?;

    let has_more = rows.len() as i64 > limit;
    let slice_len = rows.len().min(limit as usize);
    let rows_slice = &rows[..slice_len];
    let next_cursor = match rows_slice.last() {
        Some(r) => r.try_get::<i64, _>("revision")?,
        None => query.after,
    };

    let mut changes = Vec::with_capacity(rows_slice.len());
    for r in rows_slice {
        let recorded_at: DateTime<Utc> = r.try_get("recorded_at")?;
        changes.push(json!({
            "revision": r.try_get::<i64, _>("revision")?,
            "itemId": r.try_get::<String, _>("item_id")?,
            "operation": r.try_get::<String, _>("operation")?,
            "snapshot": r.try_get::<Value, _>("snapshot")?,
            "deviceId": r.try_get::<Uuid, _>("device_id")?.to_string(),
            "recordedAt": recorded_at.to_rfc3339(),
        }));
    }

    Ok(Json(json!({
        "protocolVersion": state.settings.protocol_version,
        "spaceId": space.try_get::<Uuid, _>("id")?.to_string(),
        "epoch": space.try_get::<Uuid, _>("epoch")?.to_string(),
        "changes": changes,
        "nextCursor": next_cursor,
        "hasMore": has_more,
        "serverRevision": space.try_get::<i64, _>("current_revision")?,
    })))
}

pub async fn sync_ack(
    State(state): State<AppState>,
    device: CurrentDevice,
    ApiJson(body): ApiJson<AckRequest>,
) -> Result<Json<Value>, AppError> {
    if body.through_revision < 0 {
        return Err(AppError::unprocessable("throughRevision 不能为负数"));
    }
    if body.epoch != device.epoch {
        return Err(AppError::conflict("同步空间已重置，请重新对账"));
    }
    let target = body.through_revision.clamp(0, device.current_revision);
    sqlx::query(
        r#"UPDATE devices SET last_pulled_revision=GREATEST(last_pulled_revision, $1), last_seen_at=now()
           WHERE space_id=$2 AND id=$3"#,
    )
    .bind(target)
    .bind(device.space_id)
    .bind(device.device_id)
    .execute(&state.pool)
    .await?;

    Ok(Json(json!({ "ok": true })))
}

pub async fn revoke_device(
    State(state): State<AppState>,
    device: CurrentDevice,
) -> Result<Json<Value>, AppError> {
    let token_hash = hash_token(&device.bearer_token);
    sqlx::query(
        "UPDATE devices SET revoked_at=now() WHERE space_id=$1 AND id=$2 AND token_hash=$3",
    )
    .bind(device.space_id)
    .bind(device.device_id)
    .bind(&token_hash)
    .execute(&state.pool)
    .await?;
    Ok(Json(json!({ "revoked": true })))
}

#[allow(dead_code)]
fn _keep_hashmap_import(_: HashMap<String, String>) {}
