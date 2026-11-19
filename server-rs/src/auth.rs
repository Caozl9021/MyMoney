use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use axum::extract::FromRequestParts;
use axum::http::request::Parts;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rand::RngCore;
use sha2::{Digest, Sha256};
use sqlx::Row;
use subtle::ConstantTimeEq;
use uuid::Uuid;

use crate::error::AppError;
use crate::state::AppState;

/// 管理台会话令牌：解锁时用管理密码换一枚短期随机令牌，前端只持久化这枚令牌
/// （sessionStorage，关闭标签页即失效）。服务重启会清空全部会话。
#[derive(Default)]
pub struct AdminSessions {
    inner: Mutex<HashMap<String, Instant>>,
}

impl AdminSessions {
    pub fn new() -> Self {
        Self::default()
    }

    /// 生成会话令牌并返回 `(token, ttl_seconds)`；键只存 SHA-256，不保留明文。
    pub fn issue(&self, ttl: Duration) -> (String, u64) {
        let token = new_token();
        let key = hash_token(&token);
        let now = Instant::now();
        let mut guard = self.inner.lock().expect("admin sessions poisoned");
        // 顺带清扫过期会话，避免长期运行后无限增长。
        guard.retain(|_, expires| *expires > now);
        guard.insert(key, now + ttl);
        (token, ttl.as_secs())
    }

    pub fn validate(&self, token: &str) -> bool {
        if token.is_empty() {
            return false;
        }
        let key = hash_token(token);
        let now = Instant::now();
        let mut guard = self.inner.lock().expect("admin sessions poisoned");
        match guard.get(&key) {
            Some(expires) if *expires > now => true,
            Some(_) => {
                guard.remove(&key);
                false
            }
            None => false,
        }
    }

    pub fn revoke(&self, token: &str) {
        if token.is_empty() {
            return;
        }
        self.revoke_hash(&hash_token(token));
    }

    pub fn revoke_hash(&self, key: &str) {
        if key.is_empty() {
            return;
        }
        self.inner
            .lock()
            .expect("admin sessions poisoned")
            .remove(key);
    }
}

pub fn hash_token(token: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(token.as_bytes());
    hex::encode(hasher.finalize())
}

pub fn sha256_bytes(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    hex::encode(hasher.finalize())
}

pub fn new_token() -> String {
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

pub fn constant_time_eq(left: &str, right: &str) -> bool {
    let a = left.as_bytes();
    let b = right.as_bytes();
    if a.len() != b.len() {
        return false;
    }
    a.ct_eq(b).into()
}

#[derive(Clone, Debug)]
pub struct CurrentDevice {
    pub space_id: Uuid,
    pub device_id: Uuid,
    pub device_name: String,
    pub last_pulled_revision: i64,
    pub epoch: Uuid,
    pub current_revision: i64,
    pub bearer_token: String,
}

impl FromRequestParts<AppState> for CurrentDevice {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let auth = parts
            .headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        if !auth.starts_with("Bearer ") {
            return Err(AppError::unauthorized("未登录"));
        }
        let token = auth.trim_start_matches("Bearer ").trim();
        if token.is_empty() {
            return Err(AppError::unauthorized("未登录"));
        }
        let token_hash = hash_token(token);
        let row = sqlx::query(
            r#"SELECT d.space_id, d.id AS device_id, d.name AS device_name,
                      d.last_pulled_revision, s.epoch, s.current_revision
               FROM devices d JOIN sync_spaces s ON s.id = d.space_id
               WHERE d.token_hash = $1 AND d.revoked_at IS NULL"#,
        )
        .bind(&token_hash)
        .fetch_optional(&state.pool)
        .await?;

        let Some(row) = row else {
            return Err(AppError::unauthorized("设备令牌无效或已撤销"));
        };

        Ok(Self {
            space_id: row.try_get("space_id")?,
            device_id: row.try_get("device_id")?,
            device_name: row.try_get("device_name")?,
            last_pulled_revision: row.try_get("last_pulled_revision")?,
            epoch: row.try_get("epoch")?,
            current_revision: row.try_get("current_revision")?,
            bearer_token: token.to_string(),
        })
    }
}

#[derive(Clone, Debug)]
pub struct AdminAuth {
    /// 通过短期会话令牌认证时保存其哈希（用于退出登录）；管理密码直连时为 None。
    pub session_hash: Option<String>,
}

impl AdminAuth {
    /// 是否使用管理密码本身（而不是短期会话）认证。
    pub fn is_master(&self) -> bool {
        self.session_hash.is_none()
    }
}

impl FromRequestParts<AppState> for AdminAuth {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let expected = state.settings.admin_token.as_str();
        let provided = parts
            .headers
            .get("x-admin-token")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        if provided.is_empty() {
            return Err(AppError::forbidden("管理令牌无效"));
        }
        if !expected.is_empty() && constant_time_eq(expected, provided) {
            return Ok(Self { session_hash: None });
        }
        if state.admin_sessions.validate(provided) {
            return Ok(Self {
                session_hash: Some(hash_token(provided)),
            });
        }
        Err(AppError::forbidden("管理令牌无效"))
    }
}
