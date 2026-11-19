use axum::extract::{Query, State};
use axum::http::HeaderMap;
use axum::Json;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use chrono::{DateTime, Duration, Utc};
use hmac::{Hmac, Mac};
use rand::RngCore;
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::Sha256;
use sqlx::{PgPool, Postgres, Row, Transaction};
use uuid::Uuid;

use crate::auth::{hash_token, sha256_bytes, CurrentDevice};
use crate::backup::storage::latest_backup_metadata;
use crate::error::{python_json_dumps_sorted_ascii, ApiJson, AppError};
use crate::state::AppState;
use crate::sync::rules::ensure_space;

type HmacSha256 = Hmac<Sha256>;

fn bearer_token(headers: &HeaderMap) -> Result<&str, AppError> {
    let auth = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if !auth.starts_with("Bearer ") {
        return Err(AppError::unauthorized("请重新连接"));
    }
    let token = auth.trim_start_matches("Bearer ").trim();
    if token.is_empty() {
        return Err(AppError::unauthorized("请重新连接"));
    }
    Ok(token)
}

fn identity_json(row: &sqlx::postgres::PgRow) -> Result<Value, AppError> {
    let id: Uuid = row.try_get("id")?;
    let space_id: Uuid = row.try_get("space_id")?;
    let epoch: Uuid = row.try_get("epoch")?;
    let gen: i64 = row.try_get("credential_generation")?;
    let name: String = row.try_get("name")?;
    Ok(json!({
        "deviceId": id.to_string(),
        "spaceId": space_id.to_string(),
        "epoch": epoch.to_string(),
        "credentialGeneration": gen,
        "name": name,
    }))
}

pub struct IssuedCode {
    pub code: String,
    pub invitation_id: Uuid,
    pub expires_at: DateTime<Utc>,
    pub expires_in_seconds: i64,
    pub server_time: DateTime<Utc>,
}

pub async fn issue_connection_code(
    pool: &PgPool,
    ttl_seconds: i64,
) -> Result<IssuedCode, AppError> {
    if !(30..=900).contains(&ttl_seconds) {
        return Err(AppError::unprocessable("连接码有效期须为30至900秒"));
    }
    let mut tx = pool.begin().await?;
    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
        .bind("device-enrollment-ensure-space")
        .execute(&mut *tx)
        .await?;
    let (space_id, epoch, _) = ensure_space(&mut *tx).await?;

    let mut raw = [0u8; 18];
    rand::thread_rng().fill_bytes(&mut raw);
    let code = URL_SAFE_NO_PAD.encode(raw);
    let invitation_id = Uuid::new_v4();
    let now = Utc::now();
    let expires_at = now + Duration::seconds(ttl_seconds);

    sqlx::query(
        r#"INSERT INTO device_connection_codes(code_hash,space_id,epoch,expires_at,invitation_id)
           VALUES ($1,$2,$3,$4,$5)"#,
    )
    .bind(hash_token(&code))
    .bind(space_id)
    .bind(epoch)
    .bind(expires_at)
    .bind(invitation_id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;

    Ok(IssuedCode {
        code,
        invitation_id,
        expires_at,
        expires_in_seconds: ttl_seconds,
        server_time: Utc::now(),
    })
}

async fn invitation_row<'e, E>(
    executor: E,
    invitation_id: Uuid,
    lock: bool,
) -> Result<sqlx::postgres::PgRow, AppError>
where
    E: sqlx::Executor<'e, Database = Postgres>,
{
    let sql = if lock {
        "SELECT * FROM device_connection_codes WHERE invitation_id=$1 FOR UPDATE"
    } else {
        "SELECT * FROM device_connection_codes WHERE invitation_id=$1"
    };
    sqlx::query(sql)
        .bind(invitation_id)
        .fetch_optional(executor)
        .await?
        .ok_or_else(|| AppError::not_found("邀请不存在"))
}

fn invitation_state(row: &sqlx::postgres::PgRow) -> Result<&'static str, AppError> {
    let cancelled_at: Option<DateTime<Utc>> = row.try_get("cancelled_at")?;
    if cancelled_at.is_some() {
        return Ok("cancelled");
    }
    let expires_at: DateTime<Utc> = row.try_get("expires_at")?;
    if expires_at <= Utc::now() {
        return Ok("expired");
    }
    let consumed_at: Option<DateTime<Utc>> = row.try_get("consumed_at")?;
    if consumed_at.is_some() {
        return Ok("authorized");
    }
    Ok("pending")
}

pub async fn invitation_status(pool: &PgPool, invitation_id: Uuid) -> Result<Value, AppError> {
    let row = invitation_row(pool, invitation_id, false).await?;
    let grant_hash: Option<String> = row.try_get("grant_hash")?;
    let mut completed_source: Option<Uuid> = None;
    if let Some(ref gh) = grant_hash {
        let op =
            sqlx::query("SELECT source_id FROM device_enrollment_operations WHERE grant_hash=$1")
                .bind(gh.trim())
                .fetch_optional(pool)
                .await?;
        if let Some(op_row) = op {
            completed_source = Some(op_row.try_get("source_id")?);
        }
    }
    let mut state = invitation_state(&row)?;
    if state == "authorized" && completed_source.is_some() {
        state = "completed";
    }
    let expires_at: DateTime<Utc> = row.try_get("expires_at")?;
    let inv_id: Uuid = row.try_get("invitation_id")?;

    Ok(json!({
        "invitationId": inv_id.to_string(),
        "status": state,
        "expiresAt": expires_at.to_rfc3339(),
        "serverTime": Utc::now().to_rfc3339(),
        "deviceId": completed_source.map(|u| u.to_string()),
    }))
}

pub async fn cancel_invitation(pool: &PgPool, invitation_id: Uuid) -> Result<Value, AppError> {
    let mut tx = pool.begin().await?;
    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
        .bind(format!("device-enrollment-cancel:{}", invitation_id))
        .execute(&mut *tx)
        .await?;

    let row = invitation_row(&mut *tx, invitation_id, true).await?;
    let grant_hash: Option<String> = row.try_get("grant_hash")?;
    if let Some(ref gh) = grant_hash {
        let completed =
            sqlx::query("SELECT source_id FROM device_enrollment_operations WHERE grant_hash=$1")
                .bind(gh.trim())
                .fetch_optional(&mut *tx)
                .await?;
        if let Some(op_row) = completed {
            let sid: Uuid = op_row.try_get("source_id")?;
            tx.commit().await?;
            return Ok(json!({
                "invitationId": invitation_id.to_string(),
                "status": "completed",
                "deviceId": sid.to_string(),
            }));
        }
    }

    let cancelled_at: Option<DateTime<Utc>> = row.try_get("cancelled_at")?;
    if cancelled_at.is_some() {
        tx.commit().await?;
        return Ok(json!({
            "invitationId": invitation_id.to_string(),
            "status": "cancelled",
        }));
    }

    let consumed_at: Option<DateTime<Utc>> = row.try_get("consumed_at")?;
    let expires_at: DateTime<Utc> = row.try_get("expires_at")?;
    if consumed_at.is_some() && expires_at <= Utc::now() {
        tx.commit().await?;
        return Ok(json!({
            "invitationId": invitation_id.to_string(),
            "status": "expired",
        }));
    }

    sqlx::query("UPDATE device_connection_codes SET cancelled_at=now() WHERE invitation_id=$1")
        .bind(invitation_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;

    Ok(json!({
        "invitationId": invitation_id.to_string(),
        "status": "cancelled",
    }))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupContextQuery {
    pub installation_id: Option<Uuid>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthorizeRequest {
    pub code: String,
    pub installation_id: Uuid,
    pub operation_id: Uuid,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CompleteRequest {
    pub installation_id: Uuid,
    pub operation_id: Uuid,
    pub credential: String,
    pub device_name: String,
    pub source_id: Option<Uuid>,
    pub expected_generation: Option<i64>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BindRequest {
    pub installation_id: Uuid,
}

#[derive(Deserialize)]
pub struct RenameRequest {
    pub name: String,
}

pub async fn capabilities() -> Json<Value> {
    Json(json!({
        "deviceIdentityVersion": 1,
        "ownBackupsOnly": true,
        "deleteBackups": true,
        "backupContext": true,
    }))
}

async fn authenticated_row<'e, E>(
    executor: E,
    device: &CurrentDevice,
    headers: &HeaderMap,
    lock: bool,
) -> Result<sqlx::postgres::PgRow, AppError>
where
    E: sqlx::Executor<'e, Database = Postgres>,
{
    let token = bearer_token(headers)?;
    let sql = if lock {
        r#"SELECT d.*, s.epoch FROM devices d JOIN sync_spaces s ON s.id=d.space_id
           WHERE d.space_id=$1 AND d.id=$2 AND d.revoked_at IS NULL AND d.token_hash=$3 FOR UPDATE OF d"#
    } else {
        r#"SELECT d.*, s.epoch FROM devices d JOIN sync_spaces s ON s.id=d.space_id
           WHERE d.space_id=$1 AND d.id=$2 AND d.revoked_at IS NULL AND d.token_hash=$3"#
    };
    sqlx::query(sql)
        .bind(device.space_id)
        .bind(device.device_id)
        .bind(hash_token(token))
        .fetch_optional(executor)
        .await?
        .ok_or_else(|| AppError::unauthorized("设备凭证已失效，请重新连接"))
}

pub async fn backup_context(
    State(state): State<AppState>,
    device: CurrentDevice,
    headers: HeaderMap,
    Query(query): Query<BackupContextQuery>,
) -> Result<Json<Value>, AppError> {
    let row = authenticated_row(&state.pool, &device, &headers, false).await?;
    let latest = latest_backup_metadata(&state.pool, device.space_id, device.device_id).await?;
    sqlx::query("UPDATE devices SET last_seen_at=now() WHERE space_id=$1 AND id=$2")
        .bind(device.space_id)
        .bind(device.device_id)
        .execute(&state.pool)
        .await?;

    let bound: Option<Uuid> = row.try_get("installation_id")?;
    let matches = match (bound, query.installation_id) {
        (Some(b), Some(inst)) => Some(b == inst),
        _ => None,
    };
    let identity = identity_json(&row)?;

    Ok(Json(json!({
        "identity": identity,
        "capabilities": {
            "deviceIdentityVersion": 1,
            "ownBackupsOnly": true,
            "deleteBackups": true,
            "backupContext": true,
        },
        "installationId": bound.map(|u| u.to_string()),
        "installationMatches": matches,
        "latestBackup": latest,
        "protocolVersion": 1,
    })))
}

pub async fn authorize(
    State(state): State<AppState>,
    ApiJson(body): ApiJson<AuthorizeRequest>,
) -> Result<Json<Value>, AppError> {
    let code = body.code.trim();
    if code.is_empty() || code.chars().count() > 32 {
        return Err(AppError::unprocessable("连接码格式无效"));
    }

    let mut mac = HmacSha256::new_from_slice(code.as_bytes())
        .map_err(|_| AppError::internal("授权派生失败"))?;
    mac.update(
        format!(
            "mymoney-enrollment-v1:{}:{}",
            body.installation_id, body.operation_id
        )
        .as_bytes(),
    );
    let grant = hex::encode(mac.finalize().into_bytes());

    let mut tx = state.pool.begin().await?;
    let row = sqlx::query(
        r#"SELECT c.*, s.epoch AS current_epoch FROM device_connection_codes c
           JOIN sync_spaces s ON s.id=c.space_id
           WHERE c.code_hash=$1 FOR UPDATE OF c"#,
    )
    .bind(hash_token(code))
    .fetch_optional(&mut *tx)
    .await?;

    let Some(row) = row else {
        return Err(AppError::unauthorized("连接码无效或已过期，请重新生成"));
    };
    let expires_at: DateTime<Utc> = row.try_get("expires_at")?;
    let epoch: Uuid = row.try_get("epoch")?;
    let current_epoch: Uuid = row.try_get("current_epoch")?;
    if expires_at <= Utc::now() || epoch != current_epoch {
        return Err(AppError::unauthorized("连接码无效或已过期，请重新生成"));
    }
    let cancelled_at: Option<DateTime<Utc>> = row.try_get("cancelled_at")?;
    if cancelled_at.is_some() {
        return Err(AppError::unauthorized("该连接邀请已被取消，请重新生成"));
    }

    let consumed_at: Option<DateTime<Utc>> = row.try_get("consumed_at")?;
    if consumed_at.is_some() {
        let inst: Option<Uuid> = row.try_get("installation_id")?;
        let op: Option<Uuid> = row.try_get("operation_id")?;
        if inst != Some(body.installation_id) || op != Some(body.operation_id) {
            return Err(AppError::unauthorized("连接码已使用，请重新生成"));
        }
        let grant_expires_at: Option<DateTime<Utc>> = row.try_get("grant_expires_at")?;
        if grant_expires_at.map(|g| g <= Utc::now()).unwrap_or(true) {
            return Err(AppError::unauthorized("连接授权已过期，请重新生成"));
        }
    } else {
        sqlx::query(
            r#"UPDATE device_connection_codes SET consumed_at=now(),installation_id=$1,
               operation_id=$2,grant_hash=$3,grant_expires_at=$4 WHERE code_hash=$5"#,
        )
        .bind(body.installation_id)
        .bind(body.operation_id)
        .bind(hash_token(&grant))
        .bind(Utc::now() + Duration::minutes(10))
        .bind(hash_token(code))
        .execute(&mut *tx)
        .await?;
    }
    let space_id: Uuid = row.try_get("space_id")?;
    tx.commit().await?;

    Ok(Json(json!({
        "grant": grant,
        "spaceId": space_id.to_string(),
        "epoch": epoch.to_string(),
    })))
}

async fn load_grant<'e, E>(
    executor: E,
    headers: &HeaderMap,
    lock: bool,
) -> Result<sqlx::postgres::PgRow, AppError>
where
    E: sqlx::Executor<'e, Database = Postgres>,
{
    let token = bearer_token(headers)?;
    let sql = if lock {
        r#"SELECT c.*, s.epoch AS current_epoch FROM device_connection_codes c
           JOIN sync_spaces s ON s.id=c.space_id WHERE c.grant_hash=$1 FOR UPDATE OF c"#
    } else {
        r#"SELECT c.*, s.epoch AS current_epoch FROM device_connection_codes c
           JOIN sync_spaces s ON s.id=c.space_id WHERE c.grant_hash=$1"#
    };
    let row = sqlx::query(sql)
        .bind(hash_token(token))
        .fetch_optional(executor)
        .await?
        .ok_or_else(|| AppError::unauthorized("连接授权无效或已过期，请重新生成连接码"))?;

    let grant_expires: Option<DateTime<Utc>> = row.try_get("grant_expires_at")?;
    let epoch: Uuid = row.try_get("epoch")?;
    let current_epoch: Uuid = row.try_get("current_epoch")?;
    if grant_expires.map(|g| g <= Utc::now()).unwrap_or(true) || epoch != current_epoch {
        return Err(AppError::unauthorized(
            "连接授权无效或已过期，请重新生成连接码",
        ));
    }
    let cancelled_at: Option<DateTime<Utc>> = row.try_get("cancelled_at")?;
    if cancelled_at.is_some() {
        return Err(AppError::unauthorized("该连接邀请已被取消，请重新生成"));
    }
    Ok(row)
}

pub async fn sources(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Value>, AppError> {
    let grant = load_grant(&state.pool, &headers, false).await?;
    let grant_hash: String = grant.try_get("grant_hash")?;
    let completed = sqlx::query("SELECT 1 FROM device_enrollment_operations WHERE grant_hash=$1")
        .bind(grant_hash.trim())
        .fetch_optional(&state.pool)
        .await?;
    if completed.is_some() {
        return Err(AppError::conflict("该连接授权已完成登记"));
    }

    let space_id: Uuid = grant.try_get("space_id")?;
    let rows = sqlx::query(
        r#"SELECT d.id,d.name,d.last_seen_at,d.credential_generation,
                  d.archived_at,d.revoked_at,max(b.uploaded_at) AS last_backup_at
           FROM devices d LEFT JOIN ledger_backups b
             ON b.space_id=d.space_id AND b.device_id=d.id AND b.deleted_at IS NULL
           WHERE d.space_id=$1 GROUP BY d.space_id,d.id
           ORDER BY d.archived_at NULLS FIRST,d.created_at DESC,d.id"#,
    )
    .bind(space_id)
    .fetch_all(&state.pool)
    .await?;

    let mut items = Vec::with_capacity(rows.len());
    for r in rows {
        let last_seen: Option<DateTime<Utc>> = r.try_get("last_seen_at")?;
        let last_backup: Option<DateTime<Utc>> = r.try_get("last_backup_at")?;
        let archived: Option<DateTime<Utc>> = r.try_get("archived_at")?;
        let revoked: Option<DateTime<Utc>> = r.try_get("revoked_at")?;
        items.push(json!({
            "deviceId": r.try_get::<Uuid, _>("id")?.to_string(),
            "name": r.try_get::<String, _>("name")?,
            "lastSeenAt": last_seen.map(|t| t.to_rfc3339()),
            "lastBackupAt": last_backup.map(|t| t.to_rfc3339()),
            "credentialGeneration": r.try_get::<i64, _>("credential_generation")?,
            "archived": archived.is_some(),
            "revoked": revoked.is_some(),
        }));
    }

    Ok(Json(json!({ "items": items })))
}

fn valid_credential(s: &str) -> bool {
    let len = s.len();
    (43..=128).contains(&len)
        && s.bytes()
            .all(|b| matches!(b, b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'_' | b'-'))
}

pub async fn complete(
    State(state): State<AppState>,
    headers: HeaderMap,
    ApiJson(body): ApiJson<CompleteRequest>,
) -> Result<Json<Value>, AppError> {
    if !valid_credential(&body.credential) {
        return Err(AppError::unprocessable("连接凭证格式无效"));
    }
    if body.device_name.is_empty() || body.device_name.chars().count() > 80 {
        return Err(AppError::unprocessable("设备名称无效"));
    }
    if let Some(gen) = body.expected_generation {
        if gen < 1 {
            return Err(AppError::unprocessable("设备版本无效"));
        }
    }
    let trimmed_name = body.device_name.trim().to_string();
    if trimmed_name.is_empty() {
        return Err(AppError::unprocessable("设备名称不能为空"));
    }

    let credential_hash = hash_token(&body.credential);
    let normalized = json!({
        "installationId": body.installation_id.to_string(),
        "operationId": body.operation_id.to_string(),
        "credentialHash": credential_hash,
        "deviceName": trimmed_name,
        "sourceId": body.source_id.map(|u| u.to_string()),
        "expectedGeneration": body.expected_generation,
    });
    let request_hash = sha256_bytes(python_json_dumps_sorted_ascii(&normalized).as_bytes());

    let mut tx = state.pool.begin().await?;
    let pre_grant = load_grant(&mut *tx, &headers, false).await?;
    let space_id: Uuid = pre_grant.try_get("space_id")?;

    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
        .bind(format!("device-enrollment:{}", space_id))
        .execute(&mut *tx)
        .await?;

    let grant = load_grant(&mut *tx, &headers, true).await?;
    let grant_inst: Option<Uuid> = grant.try_get("installation_id")?;
    let grant_op: Option<Uuid> = grant.try_get("operation_id")?;
    if grant_inst != Some(body.installation_id) || grant_op != Some(body.operation_id) {
        return Err(AppError::forbidden("连接授权不属于此安装或操作"));
    }
    let grant_hash: String = grant.try_get("grant_hash")?;
    let grant_hash_trimmed = grant_hash.trim().to_string();

    let previous = sqlx::query("SELECT * FROM device_enrollment_operations WHERE grant_hash=$1")
        .bind(&grant_hash_trimmed)
        .fetch_optional(&mut *tx)
        .await?;

    if let Some(prev) = previous {
        let prev_req_hash: String = prev.try_get("request_hash")?;
        if prev_req_hash.trim() != request_hash {
            return Err(AppError::conflict("该连接授权已用于不同操作"));
        }
        let prev_source_id: Uuid = prev.try_get("source_id")?;
        let prev_gen: i64 = prev.try_get("result_generation")?;
        let row = sqlx::query(
            "SELECT d.*, s.epoch FROM devices d JOIN sync_spaces s ON s.id=d.space_id WHERE d.space_id=$1 AND d.id=$2",
        )
        .bind(space_id)
        .bind(prev_source_id)
        .fetch_optional(&mut *tx)
        .await?;

        let valid = match row {
            Some(ref r) => {
                let revoked: Option<DateTime<Utc>> = r.try_get("revoked_at")?;
                let gen: i64 = r.try_get("credential_generation")?;
                let th: String = r.try_get("token_hash")?;
                let inst: Option<Uuid> = r.try_get("installation_id")?;
                revoked.is_none()
                    && gen == prev_gen
                    && th.trim() == credential_hash
                    && inst == Some(body.installation_id)
            }
            None => false,
        };
        if !valid {
            return Err(AppError::conflict(
                "设备身份已变化，请重新连接；旧操作不能恢复权限",
            ));
        }
        let ident = identity_json(row.as_ref().unwrap())?;
        tx.commit().await?;
        return Ok(Json(ident));
    }

    let owner = sqlx::query("SELECT space_id, id FROM devices WHERE token_hash=$1")
        .bind(&credential_hash)
        .fetch_optional(&mut *tx)
        .await?;
    if let Some(ow) = owner {
        let ow_space: Uuid = ow.try_get("space_id")?;
        let ow_id: Uuid = ow.try_get("id")?;
        if ow_space != space_id || body.source_id.map(|s| ow_id != s).unwrap_or(false) {
            return Err(AppError::conflict("新凭证已被其他设备使用"));
        }
    }

    let installed =
        sqlx::query("SELECT * FROM devices WHERE space_id=$1 AND installation_id=$2 FOR UPDATE")
            .bind(space_id)
            .bind(body.installation_id)
            .fetch_optional(&mut *tx)
            .await?;

    let target_source_id: Uuid = if let Some(src_id) = body.source_id {
        let Some(expected_gen) = body.expected_generation else {
            return Err(AppError::unprocessable("接替设备必须确认原设备版本"));
        };
        let row = sqlx::query("SELECT * FROM devices WHERE space_id=$1 AND id=$2 FOR UPDATE")
            .bind(space_id)
            .bind(src_id)
            .fetch_optional(&mut *tx)
            .await?
            .ok_or_else(|| AppError::not_found("原设备不存在"))?;

        let old_th: String = row.try_get("token_hash")?;
        if old_th.trim() == credential_hash {
            return Err(AppError::conflict("接替设备必须使用新的连接凭证"));
        }
        let cur_gen: i64 = row.try_get("credential_generation")?;
        if cur_gen != expected_gen {
            return Err(AppError::conflict("原设备状态已变化，请刷新后重新确认"));
        }
        if let Some(ref inst_row) = installed {
            let inst_id: Uuid = inst_row.try_get("id")?;
            if inst_id != src_id {
                return Err(AppError::conflict("此安装已属于另一设备，请先退出原连接"));
            }
        }
        sqlx::query(
            r#"UPDATE devices SET installation_id=$1,token_hash=$2,name=$3,
                 credential_generation=credential_generation+1,revoked_at=NULL,
                 archived_at=NULL,last_seen_at=now() WHERE space_id=$4 AND id=$5"#,
        )
        .bind(body.installation_id)
        .bind(&credential_hash)
        .bind(&trimmed_name)
        .bind(space_id)
        .bind(src_id)
        .execute(&mut *tx)
        .await?;
        src_id
    } else {
        if body.expected_generation.is_some() {
            return Err(AppError::unprocessable("添加新设备不需要原设备版本"));
        }
        if let Some(ref inst_row) = installed {
            let th: String = inst_row.try_get("token_hash")?;
            let revoked: Option<DateTime<Utc>> = inst_row.try_get("revoked_at")?;
            if th.trim() != credential_hash || revoked.is_some() {
                return Err(AppError::conflict(
                    "此安装已登记，请验证原凭证或明确接替原设备",
                ));
            }
            inst_row.try_get("id")?
        } else {
            let owner_again = sqlx::query("SELECT 1 FROM devices WHERE token_hash=$1")
                .bind(&credential_hash)
                .fetch_optional(&mut *tx)
                .await?;
            if owner_again.is_some() {
                return Err(AppError::conflict("新凭证已被其他设备使用"));
            }
            let new_id = Uuid::new_v4();
            sqlx::query(
                r#"INSERT INTO devices(space_id,id,name,token_hash,installation_id,last_seen_at)
                   VALUES ($1,$2,$3,$4,$5,now())"#,
            )
            .bind(space_id)
            .bind(new_id)
            .bind(&trimmed_name)
            .bind(&credential_hash)
            .bind(body.installation_id)
            .execute(&mut *tx)
            .await?;
            new_id
        }
    };

    let final_row = sqlx::query(
        "SELECT d.*, s.epoch FROM devices d JOIN sync_spaces s ON s.id=d.space_id WHERE d.space_id=$1 AND d.id=$2",
    )
    .bind(space_id)
    .bind(target_source_id)
    .fetch_one(&mut *tx)
    .await?;
    let result_gen: i64 = final_row.try_get("credential_generation")?;

    sqlx::query(
        r#"INSERT INTO device_enrollment_operations
           (grant_hash,space_id,source_id,operation_id,request_hash,credential_hash,result_generation)
           VALUES ($1,$2,$3,$4,$5,$6,$7)"#,
    )
    .bind(&grant_hash_trimmed)
    .bind(space_id)
    .bind(target_source_id)
    .bind(body.operation_id)
    .bind(&request_hash)
    .bind(&credential_hash)
    .bind(result_gen)
    .execute(&mut *tx)
    .await?;

    let ident = identity_json(&final_row)?;
    tx.commit().await?;
    Ok(Json(ident))
}

pub async fn self_device(
    State(state): State<AppState>,
    device: CurrentDevice,
    headers: HeaderMap,
) -> Result<Json<Value>, AppError> {
    let row = authenticated_row(&state.pool, &device, &headers, false).await?;
    sqlx::query("UPDATE devices SET last_seen_at=now() WHERE space_id=$1 AND id=$2")
        .bind(device.space_id)
        .bind(device.device_id)
        .execute(&state.pool)
        .await?;
    Ok(Json(identity_json(&row)?))
}

pub async fn bind_installation(
    State(state): State<AppState>,
    device: CurrentDevice,
    headers: HeaderMap,
    ApiJson(body): ApiJson<BindRequest>,
) -> Result<Json<Value>, AppError> {
    let mut tx = state.pool.begin().await?;
    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
        .bind(format!("device-enrollment:{}", device.space_id))
        .execute(&mut *tx)
        .await?;
    let row = authenticated_row(&mut *tx, &device, &headers, true).await?;
    let current_inst: Option<Uuid> = row.try_get("installation_id")?;
    if let Some(inst) = current_inst {
        if inst != body.installation_id {
            return Err(AppError::conflict(
                "此来源已绑定其他安装，请通过连接码明确接替",
            ));
        }
    }
    let occupied = sqlx::query("SELECT id FROM devices WHERE space_id=$1 AND installation_id=$2")
        .bind(device.space_id)
        .bind(body.installation_id)
        .fetch_optional(&mut *tx)
        .await?;
    if let Some(occ) = occupied {
        let occ_id: Uuid = occ.try_get("id")?;
        if occ_id != device.device_id {
            return Err(AppError::conflict("此安装已绑定其他来源"));
        }
    }
    sqlx::query(
        "UPDATE devices SET installation_id=$1,last_seen_at=now() WHERE space_id=$2 AND id=$3",
    )
    .bind(body.installation_id)
    .bind(device.space_id)
    .bind(device.device_id)
    .execute(&mut *tx)
    .await?;

    let ident = identity_json(&row)?;
    tx.commit().await?;
    Ok(Json(ident))
}

pub async fn rename(
    State(state): State<AppState>,
    device: CurrentDevice,
    headers: HeaderMap,
    ApiJson(body): ApiJson<RenameRequest>,
) -> Result<Json<Value>, AppError> {
    if body.name.is_empty() || body.name.chars().count() > 80 {
        return Err(AppError::unprocessable("设备名称无效"));
    }
    let trimmed = body.name.trim();
    if trimmed.is_empty() {
        return Err(AppError::unprocessable("设备名称不能为空"));
    }
    let mut tx = state.pool.begin().await?;
    let _ = authenticated_row(&mut *tx, &device, &headers, true).await?;
    sqlx::query("UPDATE devices SET name=$1 WHERE space_id=$2 AND id=$3")
        .bind(trimmed)
        .bind(device.space_id)
        .bind(device.device_id)
        .execute(&mut *tx)
        .await?;
    let updated = authenticated_row(&mut *tx, &device, &headers, false).await?;
    let ident = identity_json(&updated)?;
    tx.commit().await?;
    Ok(Json(ident))
}

#[allow(dead_code)]
fn _keep_tx_import(_: Option<Transaction<'_, Postgres>>) {}
