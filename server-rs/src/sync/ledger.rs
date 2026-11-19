use axum::extract::{Query, State};
use axum::http::HeaderMap;
use axum::Json;
use chrono::{DateTime, Utc};
use serde::Deserialize;
use serde_json::{json, Value};
use sqlx::{Postgres, Row, Transaction};
use uuid::Uuid;

use crate::auth::{sha256_bytes, CurrentDevice};
use crate::error::{python_json_dumps_sorted_compact, ApiJson, AppError};
use crate::state::AppState;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LedgerIdQuery {
    pub ledger_id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LedgerPullQuery {
    pub ledger_id: String,
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
pub struct LedgerAckQuery {
    pub ledger_id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LedgerAckRequest {
    pub through_revision: i64,
    pub epoch: Uuid,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LedgerPushItem {
    pub operation_id: String,
    pub epoch: Uuid,
    pub ledger_id: String,
    pub item_id: String,
    pub item_kind: String,
    pub base_revision: i64,
    pub operation: String,
    #[serde(default = "default_schema_version")]
    pub schema_version: i32,
    pub content_updated_at: Option<i64>,
    pub payload: Option<Value>,
}

fn default_schema_version() -> i32 {
    1
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LedgerPushRequest {
    #[serde(default = "default_schema_version")]
    pub protocol_version: i32,
    pub space_id: Uuid,
    pub ledger_id: String,
    pub epoch: Uuid,
    pub items: Vec<LedgerPushItem>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportSourceQuery {
    pub source_device_id: String,
    pub source_ledger_id: String,
}

fn parse_uuid(value: &str) -> Result<Uuid, AppError> {
    if value.trim().is_empty() {
        return Err(AppError::unprocessable("账本标识无效"));
    }
    Uuid::parse_str(value).map_err(|_| AppError::unprocessable("账本标识无效"))
}

async fn load_space(state: &AppState, space_id: Uuid) -> Result<Uuid, AppError> {
    let row = sqlx::query("SELECT epoch FROM sync_spaces WHERE id=$1")
        .bind(space_id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::conflict("同步空间不存在"))?;
    Ok(row.try_get("epoch")?)
}

async fn ledger_revision<'e, E>(
    executor: E,
    space_id: Uuid,
    ledger_id: Uuid,
) -> Result<i64, AppError>
where
    E: sqlx::Executor<'e, Database = Postgres>,
{
    // ledger_changes is append-only while ledger_items may be pruned after a fresh full
    // backup (realtime overlay cleanup). Taking the GREATEST of both keeps the revision
    // sequence monotonic and avoids primary-key collisions in ledger_changes.
    let row = sqlx::query(
        r#"SELECT GREATEST(
               COALESCE((SELECT MAX(revision) FROM ledger_items WHERE space_id=$1 AND ledger_id=$2), 0),
               COALESCE((SELECT MAX(revision) FROM ledger_changes WHERE space_id=$1 AND ledger_id=$2), 0)
           ) AS rev"#,
    )
    .bind(space_id)
    .bind(ledger_id)
    .fetch_one(executor)
    .await?;
    Ok(row.try_get("rev")?)
}

fn snapshot_row(row: &sqlx::postgres::PgRow) -> Result<Value, AppError> {
    let deleted_at: Option<DateTime<Utc>> = row.try_get("deleted_at")?;
    Ok(json!({
        "itemId": row.try_get::<String, _>("id")?,
        "itemKind": row.try_get::<String, _>("item_kind")?,
        "schemaVersion": row.try_get::<i32, _>("schema_version")?,
        "payload": row.try_get::<Value, _>("payload")?,
        "revision": row.try_get::<i64, _>("revision")?,
        "contentUpdatedAt": row.try_get::<Option<i64>, _>("content_updated_at")?,
        "deletedAt": deleted_at.map(|d| d.to_rfc3339()),
    }))
}

fn validate_ledger_item(item: &LedgerPushItem) -> Result<(), AppError> {
    if item.operation_id.len() < 16 || item.operation_id.len() > 96 {
        return Err(AppError::unprocessable("操作编号无效"));
    }
    if item.item_id.is_empty() || item.item_id.len() > 128 {
        return Err(AppError::unprocessable("条目编号无效"));
    }
    if item.base_revision < 0 {
        return Err(AppError::unprocessable("基础修订号无效"));
    }
    if !matches!(
        item.item_kind.as_str(),
        "bill" | "account" | "entry" | "meta"
    ) {
        return Err(AppError::unprocessable("账本条目类型无效"));
    }
    if !matches!(item.operation.as_str(), "put" | "delete" | "restore") {
        return Err(AppError::unprocessable("账本操作类型无效"));
    }
    if item.schema_version < 1 {
        return Err(AppError::unprocessable("不支持的账本格式版本"));
    }
    if item.operation == "delete" {
        return Ok(());
    }
    let Some(Value::Object(ref map)) = item.payload else {
        return Err(AppError::unprocessable("账本条目内容为空"));
    };
    let sid = map.get("syncId").and_then(|v| v.as_str()).unwrap_or("");
    if sid.is_empty() {
        return Err(AppError::unprocessable("账本条目缺少 syncId"));
    }
    Ok(())
}

fn ledger_item_hash(item: &LedgerPushItem) -> String {
    let val = json!({
        "operationId": item.operation_id,
        "epoch": item.epoch.to_string(),
        "ledgerId": item.ledger_id,
        "itemId": item.item_id,
        "itemKind": item.item_kind,
        "baseRevision": item.base_revision,
        "operation": item.operation,
        "schemaVersion": item.schema_version,
        "contentUpdatedAt": item.content_updated_at,
        "payload": item.payload,
    });
    sha256_bytes(python_json_dumps_sorted_compact(&val).as_bytes())
}

async fn record_ledger_result(
    tx: &mut Transaction<'_, Postgres>,
    space_id: Uuid,
    device_id: Uuid,
    ledger_id: Uuid,
    item: &LedgerPushItem,
    req_hash: &str,
    result: &Value,
) -> Result<(), AppError> {
    sqlx::query(
        r#"INSERT INTO ledger_operations(space_id, device_id, ledger_id, operation_id, request_hash, item_id, base_revision, result)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8)"#,
    )
    .bind(space_id)
    .bind(device_id)
    .bind(ledger_id)
    .bind(&item.operation_id)
    .bind(req_hash)
    .bind(&item.item_id)
    .bind(item.base_revision)
    .bind(result)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

pub async fn snapshot(
    State(state): State<AppState>,
    device: CurrentDevice,
    Query(query): Query<LedgerIdQuery>,
) -> Result<Json<Value>, AppError> {
    let ledger_id = parse_uuid(&query.ledger_id)?;
    let epoch = load_space(&state, device.space_id).await?;
    let limit = state.settings.max_snapshot_items;
    let rows = sqlx::query(
        "SELECT * FROM ledger_items WHERE space_id=$1 AND ledger_id=$2 ORDER BY item_kind, id LIMIT $3",
    )
    .bind(device.space_id)
    .bind(ledger_id)
    .bind(limit + 1)
    .fetch_all(&state.pool)
    .await?;

    if rows.len() as i64 > limit {
        return Err(AppError::payload_too_large(
            "账本快照超过上限，请先在 App 内整理",
        ));
    }

    let mut items = Vec::with_capacity(rows.len());
    for r in &rows {
        items.push(snapshot_row(r)?);
    }
    let rev = ledger_revision(&state.pool, device.space_id, ledger_id).await?;

    Ok(Json(json!({
        "protocolVersion": state.settings.protocol_version,
        "spaceId": device.space_id.to_string(),
        "ledgerId": ledger_id.to_string(),
        "epoch": epoch.to_string(),
        "serverRevision": rev,
        "items": items,
    })))
}

pub async fn push(
    State(state): State<AppState>,
    device: CurrentDevice,
    headers: HeaderMap,
    ApiJson(body): ApiJson<LedgerPushRequest>,
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
        return Err(AppError::unprocessable("账本同步批次条目数无效"));
    }
    let ledger_id = parse_uuid(&body.ledger_id)?;
    let space_epoch = load_space(&state, device.space_id).await?;

    let mut results = Vec::with_capacity(body.items.len());
    for item in &body.items {
        if item.epoch != space_epoch {
            return Err(AppError::conflict("同步空间已重置，请重新对账"));
        }
        let item_ledger = parse_uuid(&item.ledger_id)?;
        if item_ledger != ledger_id {
            return Err(AppError::unprocessable("账本标识不一致"));
        }
        validate_ledger_item(item)?;
        let req_hash = ledger_item_hash(item);

        let mut tx = state.pool.begin().await?;
        let previous = sqlx::query(
            r#"SELECT request_hash, result FROM ledger_operations
               WHERE space_id=$1 AND device_id=$2 AND ledger_id=$3 AND operation_id=$4"#,
        )
        .bind(device.space_id)
        .bind(device.device_id)
        .bind(ledger_id)
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

        // 用咨询锁串行化同一账本内的修订号递增，防止并发同账本冲突
        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
            .bind(format!("ledger:{}:{}", device.space_id, ledger_id))
            .execute(&mut *tx)
            .await?;

        let current = sqlx::query(
            "SELECT * FROM ledger_items WHERE space_id=$1 AND ledger_id=$2 AND id=$3 FOR UPDATE",
        )
        .bind(device.space_id)
        .bind(ledger_id)
        .bind(&item.item_id)
        .fetch_optional(&mut *tx)
        .await?;

        let current_revision: i64 = match current {
            Some(ref r) => r.try_get("revision")?,
            None => 0,
        };

        // 单真源实时投射（个人云备份）：该账本属于本设备（ledger_id == device_id），或上一次
        // 写入者就是本设备时，允许最新写入胜出（LWW）。这样重装/清缓存后 baseRevision 不一致
        // 不会把自己的 Outbox 卡成 conflict。
        let same_source = ledger_id == device.device_id
            || match current {
                Some(ref r) => {
                    r.try_get::<Option<Uuid>, _>("updated_by").unwrap_or(None)
                        == Some(device.device_id)
                }
                None => false,
            };
        let content_not_older = match current {
            Some(ref r) => {
                let existing: Option<i64> = r.try_get("content_updated_at").unwrap_or(None);
                match (item.content_updated_at, existing) {
                    (Some(incoming), Some(current)) => incoming >= current,
                    _ => true,
                }
            }
            None => true,
        };
        let fast_forward = same_source && content_not_older;

        if item.base_revision != current_revision && !fast_forward {
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
            record_ledger_result(
                &mut tx,
                device.space_id,
                device.device_id,
                ledger_id,
                item,
                &req_hash,
                &result,
            )
            .await?;
            tx.commit().await?;
            results.push(result);
            continue;
        }

        let (payload, item_kind, deleted_at): (Value, String, Option<DateTime<Utc>>) =
            if item.operation == "delete" {
                match current {
                    // 仅存在于基准快照里的老账单：直接落一条墓碑，Web Overlay 据此剔除。
                    None => {
                        if !same_source {
                            let result = json!({
                                "status": "conflict",
                                "operationId": item.operation_id,
                                "itemId": item.item_id,
                                "baseRevision": item.base_revision,
                                "remoteRevision": current_revision,
                                "remote": null,
                            });
                            record_ledger_result(
                                &mut tx,
                                device.space_id,
                                device.device_id,
                                ledger_id,
                                item,
                                &req_hash,
                                &result,
                            )
                            .await?;
                            tx.commit().await?;
                            results.push(result);
                            continue;
                        }
                        let sync_id = item
                            .payload
                            .as_ref()
                            .and_then(|p| p.get("syncId"))
                            .and_then(|v| v.as_str())
                            .map(|s| s.to_string())
                            .or_else(|| {
                                item.item_id
                                    .split_once(':')
                                    .map(|(_, rest)| rest.to_string())
                            })
                            .unwrap_or_else(|| item.item_id.clone());
                        (
                            json!({ "syncId": sync_id }),
                            item.item_kind.clone(),
                            Some(Utc::now()),
                        )
                    }
                    Some(ref r) => {
                        let prev_del: Option<DateTime<Utc>> = r.try_get("deleted_at")?;
                        if prev_del.is_some() {
                            if same_source {
                                // 幂等删除：本设备重复推送同一墓碑直接视为已应用。
                                let result = json!({
                                    "status": "ok",
                                    "operationId": item.operation_id,
                                    "itemId": item.item_id,
                                    "itemKind": r.try_get::<String, _>("item_kind")?,
                                    "revision": current_revision,
                                    "snapshot": snapshot_row(r)?,
                                });
                                record_ledger_result(
                                    &mut tx,
                                    device.space_id,
                                    device.device_id,
                                    ledger_id,
                                    item,
                                    &req_hash,
                                    &result,
                                )
                                .await?;
                                tx.commit().await?;
                                results.push(result);
                                continue;
                            }
                            let result = json!({
                                "status": "conflict",
                                "operationId": item.operation_id,
                                "itemId": item.item_id,
                                "baseRevision": item.base_revision,
                                "remoteRevision": current_revision,
                                "remote": snapshot_row(r)?,
                            });
                            record_ledger_result(
                                &mut tx,
                                device.space_id,
                                device.device_id,
                                ledger_id,
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
                            r.try_get("item_kind")?,
                            Some(Utc::now()),
                        )
                    }
                }
            } else {
                if let Some(ref r) = current {
                    let prev_del: Option<DateTime<Utc>> = r.try_get("deleted_at")?;
                    if prev_del.is_some() && item.operation != "restore" && !same_source {
                        let result = json!({
                            "status": "conflict",
                            "operationId": item.operation_id,
                            "itemId": item.item_id,
                            "baseRevision": item.base_revision,
                            "remoteRevision": current_revision,
                            "remote": snapshot_row(r)?,
                            "error": "remote_deleted",
                        });
                        record_ledger_result(
                            &mut tx,
                            device.space_id,
                            device.device_id,
                            ledger_id,
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
                    item.item_kind.clone(),
                    None,
                )
            };

        let revision = ledger_revision(&mut *tx, device.space_id, ledger_id).await? + 1;
        let saved_row = if current.is_none() {
            sqlx::query(
                r#"INSERT INTO ledger_items(space_id, ledger_id, id, item_kind, schema_version, payload,
                       revision, content_updated_at, updated_by, deleted_at)
                   VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) RETURNING *"#,
            )
            .bind(device.space_id)
            .bind(ledger_id)
            .bind(&item.item_id)
            .bind(&item_kind)
            .bind(item.schema_version)
            .bind(&payload)
            .bind(revision)
            .bind(item.content_updated_at)
            .bind(device.device_id)
            .bind(deleted_at)
            .fetch_one(&mut *tx)
            .await?
        } else {
            sqlx::query(
                r#"UPDATE ledger_items SET item_kind=$1, schema_version=$2, payload=$3,
                       revision=$4, content_updated_at=$5, updated_by=$6, deleted_at=$7
                   WHERE space_id=$8 AND ledger_id=$9 AND id=$10 RETURNING *"#,
            )
            .bind(&item_kind)
            .bind(item.schema_version)
            .bind(&payload)
            .bind(revision)
            .bind(item.content_updated_at)
            .bind(device.device_id)
            .bind(deleted_at)
            .bind(device.space_id)
            .bind(ledger_id)
            .bind(&item.item_id)
            .fetch_one(&mut *tx)
            .await?
        };

        let snap = snapshot_row(&saved_row)?;
        sqlx::query(
            r#"INSERT INTO ledger_changes(space_id, ledger_id, revision, item_id, operation, snapshot, device_id)
               VALUES ($1,$2,$3,$4,$5,$6,$7)"#,
        )
        .bind(device.space_id)
        .bind(ledger_id)
        .bind(revision)
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
            "itemKind": item_kind,
            "revision": revision,
            "snapshot": snap,
        });
        record_ledger_result(
            &mut tx,
            device.space_id,
            device.device_id,
            ledger_id,
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

pub async fn pull(
    State(state): State<AppState>,
    device: CurrentDevice,
    Query(query): Query<LedgerPullQuery>,
) -> Result<Json<Value>, AppError> {
    if query.after < 0 {
        return Err(AppError::unprocessable("after 游标不能为负数"));
    }
    if query.limit < 1 || query.limit > 100 {
        return Err(AppError::unprocessable("limit 必须在 1 到 100 之间"));
    }
    let ledger_id = parse_uuid(&query.ledger_id)?;
    let epoch = load_space(&state, device.space_id).await?;
    let limit = query.limit.clamp(1, state.settings.max_pull_items);

    let rows = sqlx::query(
        r#"SELECT revision, item_id, operation, snapshot, device_id, recorded_at
           FROM ledger_changes WHERE space_id=$1 AND ledger_id=$2 AND revision > $3
           ORDER BY revision LIMIT $4"#,
    )
    .bind(device.space_id)
    .bind(ledger_id)
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
    let server_rev = ledger_revision(&state.pool, device.space_id, ledger_id).await?;

    Ok(Json(json!({
        "protocolVersion": state.settings.protocol_version,
        "spaceId": device.space_id.to_string(),
        "ledgerId": ledger_id.to_string(),
        "epoch": epoch.to_string(),
        "changes": changes,
        "nextCursor": next_cursor,
        "hasMore": has_more,
        "serverRevision": server_rev,
    })))
}

pub async fn ack(
    State(state): State<AppState>,
    device: CurrentDevice,
    Query(query): Query<LedgerAckQuery>,
    ApiJson(body): ApiJson<LedgerAckRequest>,
) -> Result<Json<Value>, AppError> {
    if body.through_revision < 0 {
        return Err(AppError::unprocessable("throughRevision 不能为负数"));
    }
    let ledger_id = parse_uuid(&query.ledger_id)?;
    let epoch = load_space(&state, device.space_id).await?;
    if body.epoch != epoch {
        return Err(AppError::conflict("同步空间已重置，请重新对账"));
    }
    let max_rev = ledger_revision(&state.pool, device.space_id, ledger_id).await?;
    let target = body.through_revision.clamp(0, max_rev);
    sqlx::query(
        r#"INSERT INTO ledger_device_cursors(space_id, device_id, ledger_id, last_pulled_revision, updated_at)
           VALUES ($1,$2,$3,$4,now())
           ON CONFLICT (space_id, device_id, ledger_id)
           DO UPDATE SET last_pulled_revision=GREATEST(ledger_device_cursors.last_pulled_revision, EXCLUDED.last_pulled_revision), updated_at=now()"#,
    )
    .bind(device.space_id)
    .bind(device.device_id)
    .bind(ledger_id)
    .bind(target)
    .execute(&state.pool)
    .await?;

    Ok(Json(json!({ "ok": true })))
}

pub async fn import_sources(
    State(state): State<AppState>,
    device: CurrentDevice,
) -> Result<Json<Value>, AppError> {
    let _ = load_space(&state, device.space_id).await?;
    let rows = sqlx::query(
        "SELECT id, name FROM devices WHERE space_id=$1 AND id<>$2 AND revoked_at IS NULL ORDER BY created_at",
    )
    .bind(device.space_id)
    .bind(device.device_id)
    .fetch_all(&state.pool)
    .await?;

    let mut sources = Vec::new();
    for r in &rows {
        let dev_id: Uuid = r.try_get("id")?;
        let dev_name: String = r.try_get("name")?;
        let latest = sqlx::query(
            r#"SELECT ledger_id, COUNT(*) AS cnt, MAX(revision) AS rev
               FROM ledger_items WHERE space_id=$1 AND updated_by=$2 AND deleted_at IS NULL
               GROUP BY ledger_id ORDER BY rev DESC LIMIT 1"#,
        )
        .bind(device.space_id)
        .bind(dev_id)
        .fetch_optional(&state.pool)
        .await?;

        if let Some(l) = latest {
            let cnt: i64 = l.try_get("cnt")?;
            if cnt > 0 {
                let rev: Option<i64> = l.try_get("rev")?;
                sources.push(json!({
                    "deviceId": dev_id.to_string(),
                    "deviceName": dev_name,
                    "ledgerId": l.try_get::<Uuid, _>("ledger_id")?.to_string(),
                    "serverRevision": rev.unwrap_or(0),
                    "itemCount": cnt,
                }));
            }
        }
    }

    Ok(Json(json!({
        "protocolVersion": state.settings.protocol_version,
        "spaceId": device.space_id.to_string(),
        "sources": sources,
    })))
}

pub async fn import_source(
    State(state): State<AppState>,
    device: CurrentDevice,
    Query(query): Query<ImportSourceQuery>,
) -> Result<Json<Value>, AppError> {
    let source_device_id = parse_uuid(&query.source_device_id)?;
    let source_ledger_id = parse_uuid(&query.source_ledger_id)?;
    let _ = load_space(&state, device.space_id).await?;

    if source_device_id == device.device_id {
        return Err(AppError::unprocessable("不能从本设备导入"));
    }

    let dev_row =
        sqlx::query("SELECT id, name, revoked_at FROM devices WHERE space_id=$1 AND id=$2")
            .bind(device.space_id)
            .bind(source_device_id)
            .fetch_optional(&state.pool)
            .await?
            .ok_or_else(|| AppError::not_found("来源设备不存在"))?;

    let revoked: Option<DateTime<Utc>> = dev_row.try_get("revoked_at")?;
    if revoked.is_some() {
        return Err(AppError::forbidden("来源设备已撤销"));
    }

    let revision = ledger_revision(&state.pool, device.space_id, source_ledger_id).await?;
    let limit = state.settings.max_snapshot_items;
    let rows = sqlx::query(
        "SELECT * FROM ledger_items WHERE space_id=$1 AND ledger_id=$2 ORDER BY item_kind, id LIMIT $3",
    )
    .bind(device.space_id)
    .bind(source_ledger_id)
    .bind(limit + 1)
    .fetch_all(&state.pool)
    .await?;

    if rows.len() as i64 > limit {
        return Err(AppError::payload_too_large(
            "来源账本超过上限，无法整体导入",
        ));
    }

    let snapshot_id = Uuid::new_v4();
    sqlx::query(
        r#"INSERT INTO ledger_import_snapshots
           (id, space_id, source_device_id, source_ledger_id, target_device_id, source_revision, item_count)
           VALUES ($1,$2,$3,$4,$5,$6,$7)"#,
    )
    .bind(snapshot_id)
    .bind(device.space_id)
    .bind(source_device_id)
    .bind(source_ledger_id)
    .bind(device.device_id)
    .bind(revision)
    .bind(rows.len() as i32)
    .execute(&state.pool)
    .await?;

    let mut items = Vec::with_capacity(rows.len());
    for r in &rows {
        items.push(snapshot_row(r)?);
    }

    Ok(Json(json!({
        "protocolVersion": state.settings.protocol_version,
        "spaceId": device.space_id.to_string(),
        "importSnapshotId": snapshot_id.to_string(),
        "sourceLedgerId": source_ledger_id.to_string(),
        "sourceRevision": revision,
        "items": items,
    })))
}
