use std::collections::{BTreeSet, HashMap, HashSet};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use axum::body::Body;
use axum::extract::{Path as AxumPath, Query, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::Response;
use axum::Json;
use chrono::{DateTime, Utc};
use futures_util::StreamExt;
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use sqlx::{PgPool, Row};
use tokio::fs::File as TokioFile;
use uuid::Uuid;

use crate::auth::{hash_token, sha256_bytes, CurrentDevice};
use crate::backup::validation::validate_snapshot;
use crate::error::{python_json_dumps_sorted_ascii, ApiJson, AppError};
use crate::state::AppState;

pub const MAX_DELETE_BATCH: usize = 100;

const SELECT_SQL: &str = r#"SELECT b.*, d.name AS device_name FROM ledger_backups b
    JOIN devices d ON d.space_id=b.space_id AND d.id=b.device_id"#;

pub fn file_digest(path: &Path) -> Result<String, std::io::Error> {
    let mut file = File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 1024 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

pub fn backup_file_path(backup_dir: &Path, storage_key: &str) -> Result<PathBuf, AppError> {
    let root = fs::canonicalize(backup_dir).unwrap_or_else(|_| backup_dir.to_path_buf());
    let candidate = root.join(storage_key);
    let normalized = normalize_lexical(&candidate);
    if !normalized.starts_with(&root) {
        return Err(AppError::internal("备份索引路径无效"));
    }
    Ok(normalized)
}

fn normalize_lexical(path: &Path) -> PathBuf {
    use std::path::Component;
    let mut out = PathBuf::new();
    for comp in path.components() {
        match comp {
            Component::CurDir => {}
            Component::ParentDir => {
                out.pop();
            }
            c => out.push(c.as_os_str()),
        }
    }
    out
}

fn sync_directory(path: &Path) -> Result<(), std::io::Error> {
    let dir = File::open(path)?;
    dir.sync_all()
}

fn is_valid_sha256_hex(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

pub fn metadata_from_row(row: &sqlx::postgres::PgRow) -> Result<Value, AppError> {
    let id: Uuid = row.try_get("id")?;
    let device_id: Uuid = row.try_get("device_id")?;
    let device_name: String = row.try_get("device_name")?;
    let app_version: String = row.try_get("app_version")?;
    let backup_schema: i32 = row.try_get("backup_schema")?;
    let sha256: String = row.try_get("sha256")?;
    let size_bytes: i64 = row.try_get("size_bytes")?;
    let bill_count: i64 = row.try_get("bill_count")?;
    let snapshot_at: DateTime<Utc> = row.try_get("snapshot_at")?;
    let uploaded_at: DateTime<Utc> = row.try_get("uploaded_at")?;
    Ok(json!({
        "id": id.to_string(),
        "deviceId": device_id.to_string(),
        "deviceName": device_name,
        "appVersion": app_version,
        "backupSchema": backup_schema,
        "sha256": sha256.trim(),
        "sizeBytes": size_bytes,
        "billCount": bill_count,
        "snapshotAt": snapshot_at.to_rfc3339(),
        "uploadedAt": uploaded_at.to_rfc3339(),
    }))
}

pub async fn latest_backup_metadata(
    pool: &PgPool,
    space_id: Uuid,
    device_id: Uuid,
) -> Result<Option<Value>, AppError> {
    let sql = format!(
        "{} WHERE b.space_id=$1 AND b.device_id=$2 AND b.deleted_at IS NULL ORDER BY b.uploaded_at DESC, b.id DESC LIMIT 1",
        SELECT_SQL
    );
    let row = sqlx::query(&sql)
        .bind(space_id)
        .bind(device_id)
        .fetch_optional(pool)
        .await?;
    match row {
        Some(r) => Ok(Some(metadata_from_row(&r)?)),
        None => Ok(None),
    }
}

struct TempPartGuard {
    path: Option<PathBuf>,
}

impl Drop for TempPartGuard {
    fn drop(&mut self) {
        if let Some(ref p) = self.path {
            let _ = fs::remove_file(p);
        }
    }
}

pub async fn upload_backup(
    State(state): State<AppState>,
    AxumPath(backup_id): AxumPath<Uuid>,
    device: CurrentDevice,
    headers: HeaderMap,
    body: Body,
) -> Result<Json<Value>, AppError> {
    let x_space_epoch = headers
        .get("x-space-epoch")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if x_space_epoch != device.epoch.to_string() {
        return Err(AppError::conflict("家庭空间已重置，请重新对账"));
    }

    let x_content_sha256 = headers
        .get("x-content-sha256")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let x_app_version = headers
        .get("x-app-version")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if !is_valid_sha256_hex(x_content_sha256)
        || x_app_version.is_empty()
        || x_app_version.chars().count() > 80
    {
        return Err(AppError::unprocessable("备份摘要或应用版本无效"));
    }

    let content_encoding = headers
        .get(header::CONTENT_ENCODING)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("identity");
    if !content_encoding.eq_ignore_ascii_case("identity") {
        return Err(AppError::unsupported_media_type(
            "仅接受未压缩 JSON8/JSON9/JSON10 快照",
        ));
    }

    let content_type = headers
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .split(';')
        .next()
        .unwrap_or("")
        .trim()
        .to_lowercase();
    if content_type != "application/json" {
        return Err(AppError::unsupported_media_type(
            "备份必须使用 application/json",
        ));
    }

    let length: u64 = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<u64>().ok())
        .ok_or_else(|| AppError::length_required("备份缺少有效 Content-Length"))?;
    if length == 0 || length > state.settings.max_backup_bytes {
        return Err(AppError::payload_too_large("备份超过大小限制"));
    }

    let directory = state.settings.backup_dir.join(device.space_id.to_string());
    if fs::create_dir_all(&directory).is_err() {
        return Err(AppError::insufficient_storage(
            "家庭备份磁盘写入失败，已有快照保留",
        ));
    }
    let _ = sync_directory(&state.settings.backup_dir);

    let temp_name = format!(".{}-{}.part", backup_id, Uuid::new_v4().simple());
    let temp_path = directory.join(temp_name);
    let mut part_guard = TempPartGuard {
        path: Some(temp_path.clone()),
    };

    let mut file = match OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temp_path)
    {
        Ok(f) => f,
        Err(_) => {
            return Err(AppError::insufficient_storage(
                "家庭备份磁盘写入失败，已有快照保留",
            ));
        }
    };

    let mut hasher = Sha256::new();
    let mut received: u64 = 0;
    let mut stream = body.into_data_stream();
    while let Some(chunk_res) = stream.next().await {
        let chunk = chunk_res.map_err(|_| AppError::unprocessable("读取上传数据流失败"))?;
        received = received.saturating_add(chunk.len() as u64);
        if received > length || received > state.settings.max_backup_bytes {
            return Err(AppError::payload_too_large("备份超过声明长度或大小限制"));
        }
        hasher.update(&chunk);
        if file.write_all(&chunk).is_err() {
            return Err(AppError::insufficient_storage(
                "家庭备份磁盘写入失败，已有快照保留",
            ));
        }
    }
    if file.flush().is_err() || file.sync_all().is_err() {
        return Err(AppError::insufficient_storage(
            "家庭备份磁盘写入失败，已有快照保留",
        ));
    }
    drop(file);

    let actual_sha = hex::encode(hasher.finalize());
    if received != length || actual_sha != x_content_sha256 {
        return Err(AppError::unprocessable("备份长度或 SHA-256 不匹配"));
    }

    let (bill_count, snapshot_at, backup_schema) = validate_snapshot(&temp_path)?;
    let storage_key = format!("{}/{}.json", device.space_id, backup_id);
    let final_path = backup_file_path(&state.settings.backup_dir, &storage_key)?;

    let mut tx = state.pool.begin().await?;
    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
        .bind(&storage_key)
        .execute(&mut *tx)
        .await?;

    let token_hash = hash_token(&device.bearer_token);
    let active = sqlx::query(
        r#"SELECT s.epoch FROM devices d JOIN sync_spaces s ON s.id=d.space_id
           WHERE d.space_id=$1 AND d.id=$2 AND d.revoked_at IS NULL
             AND d.token_hash=$3 FOR SHARE OF d,s"#,
    )
    .bind(device.space_id)
    .bind(device.device_id)
    .bind(&token_hash)
    .fetch_optional(&mut *tx)
    .await?;

    let Some(active_row) = active else {
        return Err(AppError::unauthorized("设备令牌已撤销"));
    };
    let active_epoch: Uuid = active_row.try_get("epoch")?;
    if active_epoch.to_string() != x_space_epoch {
        return Err(AppError::conflict("家庭空间已重置，请重新对账"));
    }

    let prev_sql = format!("{} WHERE b.space_id=$1 AND b.id=$2", SELECT_SQL);
    let previous = sqlx::query(&prev_sql)
        .bind(device.space_id)
        .bind(backup_id)
        .fetch_optional(&mut *tx)
        .await?;

    if let Some(prev) = previous {
        let prev_device_id: Uuid = prev.try_get("device_id")?;
        if prev_device_id != device.device_id {
            return Err(AppError::not_found("备份不存在"));
        }
        let prev_deleted: Option<DateTime<Utc>> = prev.try_get("deleted_at")?;
        if prev_deleted.is_some() {
            return Err(AppError::gone("此备份已删除，不能重新上传"));
        }
        let prev_sha: String = prev.try_get("sha256")?;
        if prev_sha.trim() != x_content_sha256 {
            return Err(AppError::conflict("备份标识已用于不同内容"));
        }

        let needs_repair = match fs::metadata(&final_path) {
            Ok(meta) if meta.is_file() && meta.len() == received => {
                file_digest(&final_path).ok().as_deref() != Some(x_content_sha256)
            }
            _ => true,
        };
        if needs_repair {
            if fs::rename(&temp_path, &final_path).is_err() || sync_directory(&directory).is_err() {
                return Err(AppError::insufficient_storage(
                    "家庭备份磁盘写入失败，已有快照保留",
                ));
            }
            part_guard.path = None;
        }
        let meta = metadata_from_row(&prev)?;
        tx.commit().await?;
        return Ok(Json(meta));
    }

    if final_path.exists() {
        let matches = match fs::metadata(&final_path) {
            Ok(meta) if meta.len() == received => {
                file_digest(&final_path).ok().as_deref() == Some(x_content_sha256)
            }
            _ => false,
        };
        if !matches {
            return Err(AppError::conflict("备份标识存在不同内容的孤立文件"));
        }
    } else {
        if fs::rename(&temp_path, &final_path).is_err() || sync_directory(&directory).is_err() {
            return Err(AppError::insufficient_storage(
                "家庭备份磁盘写入失败，已有快照保留",
            ));
        }
        part_guard.path = None;
    }

    sqlx::query(
        r#"INSERT INTO ledger_backups(space_id,id,device_id,app_version,backup_schema,storage_key,sha256,
              size_bytes,bill_count,snapshot_at)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)"#,
    )
    .bind(device.space_id)
    .bind(backup_id)
    .bind(device.device_id)
    .bind(x_app_version)
    .bind(backup_schema)
    .bind(&storage_key)
    .bind(x_content_sha256)
    .bind(received as i64)
    .bind(bill_count)
    .bind(snapshot_at)
    .execute(&mut *tx)
    .await?;

    let snapshot_at_ms = snapshot_at.timestamp_millis();

    // 实时增量自洁：这份全量快照已经包含导出时刻之前的所有状态，早于它的增量行
    // 不再需要，删掉后 ledger_items 永远只保留“自上次完整备份以来”的差量。
    // 非删除行：快照已包含 → 清理；墓碑：删除事件早于快照导出 → 快照里本来就没有它，可清理。
    // 删除晚于快照的墓碑必须保留，否则 Web 仍会显示已删账单。
    sqlx::query(
        r#"DELETE FROM ledger_items
           WHERE space_id=$1 AND ledger_id=$2 AND (
                 (deleted_at IS NULL AND content_updated_at IS NOT NULL AND content_updated_at <= $3)
              OR (deleted_at IS NOT NULL AND deleted_at <= to_timestamp($3::double precision / 1000.0))
           )"#,
    )
    .bind(device.space_id)
    .bind(device.device_id)
    .bind(snapshot_at_ms)
    .execute(&mut *tx)
    .await?;

    let inserted = sqlx::query(&prev_sql)
        .bind(device.space_id)
        .bind(backup_id)
        .fetch_one(&mut *tx)
        .await?;
    let meta = metadata_from_row(&inserted)?;
    tx.commit().await?;
    Ok(Json(meta))
}

#[derive(Deserialize)]
pub struct ListQuery {
    #[serde(default)]
    pub offset: i64,
    #[serde(default = "default_list_limit")]
    pub limit: i64,
}

fn default_list_limit() -> i64 {
    50
}

pub async fn list_backups(
    State(state): State<AppState>,
    device: CurrentDevice,
    Query(query): Query<ListQuery>,
) -> Result<Json<Value>, AppError> {
    if query.offset < 0 {
        return Err(AppError::unprocessable("offset 不能为负数"));
    }
    if query.limit < 1 || query.limit > 100 {
        return Err(AppError::unprocessable("limit 必须在 1 到 100 之间"));
    }

    let sql = format!(
        "{} WHERE b.space_id=$1 AND b.device_id=$2 AND b.deleted_at IS NULL ORDER BY b.uploaded_at DESC,b.id DESC LIMIT $3 OFFSET $4",
        SELECT_SQL
    );
    let rows = sqlx::query(&sql)
        .bind(device.space_id)
        .bind(device.device_id)
        .bind(query.limit + 1)
        .bind(query.offset)
        .fetch_all(&state.pool)
        .await?;

    let has_more = rows.len() as i64 > query.limit;
    let slice_len = rows.len().min(query.limit as usize);
    let mut items = Vec::with_capacity(slice_len);
    for r in &rows[..slice_len] {
        items.push(metadata_from_row(r)?);
    }

    Ok(Json(json!({
        "protocolVersion": 1,
        "spaceId": device.space_id.to_string(),
        "epoch": device.epoch.to_string(),
        "items": items,
        "hasMore": has_more,
        "nextOffset": query.offset + slice_len as i64,
    })))
}

pub async fn stream_verified_backup_file(
    backup_dir: &Path,
    backup_id: Uuid,
    storage_key: &str,
    expected_size: i64,
    expected_sha: &str,
) -> Result<Response, AppError> {
    let path = backup_file_path(backup_dir, storage_key)?;
    let expected_sha = expected_sha.trim();
    let ok = match fs::metadata(&path) {
        Ok(meta) if meta.is_file() && meta.len() as i64 == expected_size => {
            file_digest(&path).ok().as_deref() == Some(expected_sha)
        }
        Ok(_) => false,
        Err(_) => {
            return Err(AppError::conflict("备份文件不可用，请恢复家庭服务文件卷"));
        }
    };
    if !ok {
        return Err(AppError::conflict("备份文件损坏，请恢复家庭服务文件卷"));
    }

    let tokio_file = TokioFile::open(&path)
        .await
        .map_err(|_| AppError::conflict("备份文件不可用，请恢复家庭服务文件卷"))?;
    let stream = tokio_file_stream(tokio_file);
    let body = Body::from_stream(stream);

    let mut resp = Response::new(body);
    *resp.status_mut() = StatusCode::OK;
    let headers = resp.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json"),
    );
    if let Ok(disp) = HeaderValue::from_str(&format!(
        "attachment; filename=\"MyMoney-{}.json\"",
        backup_id
    )) {
        headers.insert(header::CONTENT_DISPOSITION, disp);
    }
    if let Ok(sha_val) = HeaderValue::from_str(expected_sha) {
        headers.insert("x-content-sha256", sha_val);
    }
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    Ok(resp)
}

fn tokio_file_stream(
    file: TokioFile,
) -> impl futures_util::Stream<Item = Result<bytes::Bytes, std::io::Error>> + Send {
    let buf = vec![0u8; 64 * 1024];
    futures_util::stream::unfold((file, buf), |(mut file, mut buf)| async move {
        use tokio::io::AsyncReadExt;
        match file.read(&mut buf).await {
            Ok(0) => None,
            Ok(n) => {
                let chunk = bytes::Bytes::copy_from_slice(&buf[..n]);
                Some((Ok(chunk), (file, buf)))
            }
            Err(e) => Some((Err(e), (file, buf))),
        }
    })
}

pub async fn download_backup(
    State(state): State<AppState>,
    AxumPath(backup_id): AxumPath<Uuid>,
    device: CurrentDevice,
) -> Result<Response, AppError> {
    let sql = format!(
        "{} WHERE b.space_id=$1 AND b.id=$2 AND b.device_id=$3 AND b.deleted_at IS NULL",
        SELECT_SQL
    );
    let row = sqlx::query(&sql)
        .bind(device.space_id)
        .bind(backup_id)
        .bind(device.device_id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::not_found("备份不存在"))?;

    let storage_key: String = row.try_get("storage_key")?;
    let size_bytes: i64 = row.try_get("size_bytes")?;
    let sha256: String = row.try_get("sha256")?;
    stream_verified_backup_file(
        &state.settings.backup_dir,
        backup_id,
        &storage_key,
        size_bytes,
        &sha256,
    )
    .await
}

pub async fn backup_status(
    State(state): State<AppState>,
    AxumPath(backup_id): AxumPath<Uuid>,
    device: CurrentDevice,
) -> Result<Json<Value>, AppError> {
    let sql = format!(
        "{} WHERE b.space_id=$1 AND b.id=$2 AND b.device_id=$3 AND b.deleted_at IS NULL",
        SELECT_SQL
    );
    let row = sqlx::query(&sql)
        .bind(device.space_id)
        .bind(backup_id)
        .bind(device.device_id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::not_found("备份不存在或已删除"))?;

    Ok(Json(metadata_from_row(&row)?))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeleteRequest {
    pub operation_id: Uuid,
    pub backup_ids: Vec<Uuid>,
}

struct TombstoneRow {
    space_id: Uuid,
    id: Uuid,
    storage_key: String,
    size_bytes: i64,
    recycled_at: Option<DateTime<Utc>>,
}

fn summarize_recycle(backup_dir: &Path, rows: &[TombstoneRow]) -> Result<Value, AppError> {
    let mut recycled_bytes: i64 = 0;
    let mut pending_bytes: i64 = 0;
    for r in rows {
        let path = backup_file_path(backup_dir, &r.storage_key)?;
        let gone = r.recycled_at.is_some() || !path.is_file();
        if gone {
            recycled_bytes += r.size_bytes;
        } else {
            pending_bytes += r.size_bytes;
        }
    }
    Ok(json!({
        "deletedCount": rows.len(),
        "deletedBytes": recycled_bytes + pending_bytes,
        "recycledBytes": recycled_bytes,
        "pendingBytes": pending_bytes,
        "recyclePending": pending_bytes > 0,
    }))
}

async fn recycle_files(pool: &PgPool, backup_dir: &Path, rows: &mut [TombstoneRow]) {
    for r in rows.iter_mut() {
        let Ok(path) = backup_file_path(backup_dir, &r.storage_key) else {
            continue;
        };
        if !path.is_file() {
            continue;
        }
        if fs::remove_file(&path).is_err() {
            continue;
        }
        if let Some(parent) = path.parent() {
            let _ = sync_directory(parent);
        }
        let updated = sqlx::query(
            r#"UPDATE ledger_backups SET recycled_at=now()
               WHERE space_id=$1 AND id=$2 AND deleted_at IS NOT NULL AND recycled_at IS NULL
               RETURNING recycled_at"#,
        )
        .bind(r.space_id)
        .bind(r.id)
        .fetch_optional(pool)
        .await;
        if let Ok(Some(row)) = updated {
            r.recycled_at = row.try_get("recycled_at").ok().flatten();
        }
    }
}

pub async fn recycle_pending_backups(pool: &PgPool, backup_dir: &Path) -> Result<Value, AppError> {
    let rows = sqlx::query(
        r#"SELECT b.space_id, b.id, b.storage_key, b.size_bytes, b.recycled_at
           FROM ledger_backups b
           JOIN devices d ON d.space_id=b.space_id AND d.id=b.device_id
           WHERE b.deleted_at IS NOT NULL AND b.recycled_at IS NULL
           ORDER BY b.deleted_at"#,
    )
    .fetch_all(pool)
    .await?;

    let mut pending = Vec::new();
    for r in rows {
        let storage_key: String = r.try_get("storage_key")?;
        if let Ok(path) = backup_file_path(backup_dir, &storage_key) {
            if path.is_file() {
                pending.push(TombstoneRow {
                    space_id: r.try_get("space_id")?,
                    id: r.try_get("id")?,
                    storage_key,
                    size_bytes: r.try_get("size_bytes")?,
                    recycled_at: r.try_get("recycled_at")?,
                });
            }
        }
    }
    let swept = pending.len();
    recycle_files(pool, backup_dir, &mut pending).await;

    let rem = sqlx::query(
        "SELECT count(*) AS n, coalesce(sum(size_bytes),0)::bigint AS bytes FROM ledger_backups WHERE deleted_at IS NOT NULL AND recycled_at IS NULL",
    )
    .fetch_one(pool)
    .await?;
    let pending_count: i64 = rem.try_get("n")?;
    let pending_bytes: i64 = rem.try_get("bytes")?;

    Ok(json!({
        "swept": swept,
        "pendingCount": pending_count,
        "pendingBytes": pending_bytes,
    }))
}

pub async fn delete_backups(
    State(state): State<AppState>,
    device: CurrentDevice,
    ApiJson(body): ApiJson<DeleteRequest>,
) -> Result<Json<Value>, AppError> {
    let unique_sorted: BTreeSet<String> = body.backup_ids.iter().map(|u| u.to_string()).collect();
    if unique_sorted.is_empty() || unique_sorted.len() > MAX_DELETE_BATCH {
        return Err(AppError::unprocessable(format!(
            "单次最多删除{}份备份",
            MAX_DELETE_BATCH
        )));
    }
    let ids_str: Vec<String> = unique_sorted.into_iter().collect();
    let ids_uuid: Vec<Uuid> = ids_str
        .iter()
        .filter_map(|s| Uuid::parse_str(s).ok())
        .collect();

    let hash_input = json!({
        "operationId": body.operation_id.to_string(),
        "sourceId": device.device_id.to_string(),
        "backupIds": ids_str,
    });
    let request_hash = sha256_bytes(python_json_dumps_sorted_ascii(&hash_input).as_bytes());
    let token_hash = hash_token(&device.bearer_token);

    let mut tx = state.pool.begin().await?;
    for id_s in &ids_str {
        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
            .bind(format!("{}/{}.json", device.space_id, id_s))
            .execute(&mut *tx)
            .await?;
    }

    let active = sqlx::query(
        r#"SELECT s.epoch FROM devices d JOIN sync_spaces s ON s.id=d.space_id
           WHERE d.space_id=$1 AND d.id=$2 AND d.revoked_at IS NULL
             AND d.token_hash=$3 FOR SHARE OF d,s"#,
    )
    .bind(device.space_id)
    .bind(device.device_id)
    .bind(&token_hash)
    .fetch_optional(&mut *tx)
    .await?;
    if active.is_none() {
        return Err(AppError::unauthorized("设备令牌已撤销"));
    }

    let previous = sqlx::query(
        r#"SELECT request_hash FROM backup_delete_operations
           WHERE space_id=$1 AND source_id=$2 AND operation_id=$3"#,
    )
    .bind(device.space_id)
    .bind(device.device_id)
    .bind(body.operation_id)
    .fetch_optional(&mut *tx)
    .await?;

    if let Some(prev) = previous {
        let prev_hash: String = prev.try_get("request_hash")?;
        if prev_hash.trim() != request_hash {
            return Err(AppError::conflict("该操作已用于不同的删除请求"));
        }
        let rows = sqlx::query(
            r#"SELECT space_id, id, storage_key, size_bytes, recycled_at
               FROM ledger_backups WHERE space_id=$1 AND id = ANY($2)"#,
        )
        .bind(device.space_id)
        .bind(&ids_uuid)
        .fetch_all(&mut *tx)
        .await?;
        let mut tombstones = Vec::with_capacity(rows.len());
        for r in rows {
            tombstones.push(TombstoneRow {
                space_id: r.try_get("space_id")?,
                id: r.try_get("id")?,
                storage_key: r.try_get("storage_key")?,
                size_bytes: r.try_get("size_bytes")?,
                recycled_at: r.try_get("recycled_at")?,
            });
        }
        tx.commit().await?;
        let mut result = summarize_recycle(&state.settings.backup_dir, &tombstones)?;
        if let Some(obj) = result.as_object_mut() {
            obj.insert(
                "operationId".to_string(),
                json!(body.operation_id.to_string()),
            );
        }
        return Ok(Json(result));
    }

    let rows = sqlx::query(
        r#"SELECT b.space_id, b.id, b.device_id, b.storage_key, b.size_bytes, b.deleted_at, b.recycled_at
           FROM ledger_backups b WHERE b.space_id=$1 AND b.id = ANY($2)
           ORDER BY b.uploaded_at DESC, b.id DESC FOR UPDATE"#,
    )
    .bind(device.space_id)
    .bind(&ids_uuid)
    .fetch_all(&mut *tx)
    .await?;

    let mut by_id: HashMap<Uuid, &sqlx::postgres::PgRow> = HashMap::with_capacity(rows.len());
    for r in &rows {
        let id: Uuid = r.try_get("id")?;
        by_id.insert(id, r);
    }

    let mut total_bytes: i64 = 0;
    let mut tombstones = Vec::with_capacity(ids_uuid.len());
    for target_id in &ids_uuid {
        let Some(row) = by_id.get(target_id) else {
            return Err(AppError::not_found("备份不存在或已删除"));
        };
        let row_device: Uuid = row.try_get("device_id")?;
        if row_device != device.device_id {
            return Err(AppError::not_found("备份不存在或已删除"));
        }
        let deleted_at: Option<DateTime<Utc>> = row.try_get("deleted_at")?;
        if deleted_at.is_some() {
            return Err(AppError::conflict("所选备份已被删除，请刷新后重试"));
        }
        let sz: i64 = row.try_get("size_bytes")?;
        total_bytes += sz;
        tombstones.push(TombstoneRow {
            space_id: row.try_get("space_id")?,
            id: *target_id,
            storage_key: row.try_get("storage_key")?,
            size_bytes: sz,
            recycled_at: row.try_get("recycled_at")?,
        });
    }

    sqlx::query(
        r#"UPDATE ledger_backups SET deleted_at=now()
           WHERE space_id=$1 AND id = ANY($2) AND deleted_at IS NULL"#,
    )
    .bind(device.space_id)
    .bind(&ids_uuid)
    .execute(&mut *tx)
    .await?;

    sqlx::query(
        r#"INSERT INTO backup_delete_operations
           (space_id,source_id,operation_id,request_hash,deleted_backup_ids,deleted_bytes)
           VALUES ($1,$2,$3,$4,$5,$6)"#,
    )
    .bind(device.space_id)
    .bind(device.device_id)
    .bind(body.operation_id)
    .bind(&request_hash)
    .bind(&ids_uuid)
    .bind(total_bytes)
    .execute(&mut *tx)
    .await?;

    tx.commit().await?;

    recycle_files(&state.pool, &state.settings.backup_dir, &mut tombstones).await;
    let mut result = summarize_recycle(&state.settings.backup_dir, &tombstones)?;
    if let Some(obj) = result.as_object_mut() {
        obj.insert(
            "operationId".to_string(),
            json!(body.operation_id.to_string()),
        );
    }
    Ok(Json(result))
}

pub async fn audit_files(pool: &PgPool, backup_dir: &Path) -> Result<Value, AppError> {
    let rows = sqlx::query(
        "SELECT storage_key,size_bytes,sha256,deleted_at,recycled_at FROM ledger_backups",
    )
    .fetch_all(pool)
    .await?;

    let mut indexed = HashSet::new();
    let mut tombstone_keys = HashSet::new();
    let mut missing = Vec::new();
    let mut corrupt = Vec::new();
    let mut pending = Vec::new();
    let mut live_count: usize = 0;
    let mut deleted_count: usize = 0;

    for row in rows {
        let storage_key: String = row.try_get("storage_key")?;
        let size_bytes: i64 = row.try_get("size_bytes")?;
        let sha256: String = row.try_get("sha256")?;
        let deleted_at: Option<DateTime<Utc>> = row.try_get("deleted_at")?;
        let recycled_at: Option<DateTime<Utc>> = row.try_get("recycled_at")?;

        if deleted_at.is_none() {
            live_count += 1;
            indexed.insert(storage_key.clone());
            let path = backup_file_path(backup_dir, &storage_key)?;
            match fs::metadata(&path) {
                Ok(meta) if meta.is_file() => {
                    if meta.len() as i64 != size_bytes
                        || file_digest(&path).ok().as_deref() != Some(sha256.trim())
                    {
                        corrupt.push(storage_key);
                    }
                }
                _ => missing.push(storage_key),
            }
        } else {
            deleted_count += 1;
            tombstone_keys.insert(storage_key.clone());
            if recycled_at.is_none() {
                if let Ok(path) = backup_file_path(backup_dir, &storage_key) {
                    if path.is_file() {
                        pending.push(storage_key);
                    }
                }
            }
        }
    }

    let mut complete = HashSet::new();
    let mut partial = Vec::new();
    if let Ok(space_dirs) = fs::read_dir(backup_dir) {
        for space_entry in space_dirs.flatten() {
            let space_path = space_entry.path();
            if !space_path.is_dir() {
                continue;
            }
            if let Ok(files) = fs::read_dir(&space_path) {
                for file_entry in files.flatten() {
                    let fpath = file_entry.path();
                    if !fpath.is_file() {
                        continue;
                    }
                    let rel = match fpath.strip_prefix(backup_dir) {
                        Ok(r) => r.to_string_lossy().to_string(),
                        Err(_) => continue,
                    };
                    if let Some(ext) = fpath.extension().and_then(|e| e.to_str()) {
                        if ext == "json" {
                            complete.insert(rel);
                        } else if ext == "part" {
                            partial.push(rel);
                        }
                    }
                }
            }
        }
    }

    let mut orphan: Vec<String> = complete
        .into_iter()
        .filter(|k| !indexed.contains(k) && !tombstone_keys.contains(k))
        .collect();
    orphan.sort();
    partial.sort();
    missing.sort();
    corrupt.sort();
    pending.sort();

    Ok(json!({
        "indexedCount": live_count,
        "orphanFiles": orphan,
        "partialFiles": partial,
        "missingFiles": missing,
        "corruptFiles": corrupt,
        "deletedCount": deleted_count,
        "pendingRecycleFiles": pending,
    }))
}
