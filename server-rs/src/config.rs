use std::collections::HashMap;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Clone, Debug)]
pub struct Settings {
    pub database_url: String,
    pub schema: String,
    pub backup_dir: PathBuf,
    pub admin_token: String,
    pub pairing_code: Option<String>,
    pub pairing_ttl_seconds: i64,
    pub protocol_version: i32,
    pub max_push_items: usize,
    pub max_push_bytes: usize,
    pub max_pull_items: i64,
    pub max_snapshot_items: i64,
    pub max_backup_bytes: u64,
    pub port: u16,
    pub base_url: String,
    pub server_url: String,
    pub ctrl_url: String,
    pub tskey: String,
}

pub fn validate_schema_name(schema: &str) -> Result<(), String> {
    if schema.is_empty()
        || !schema
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_')
    {
        return Err("MYMONEY_DB_SCHEMA 只能是字母、数字和下划线".to_string());
    }
    Ok(())
}

/// 逐行解析 `.env` 文件（不经 shell 展开，保留密码中的特殊字符），仅填充尚未在环境中存在的键。
pub fn load_env_file_defaults(path: &Path) -> Result<HashMap<String, String>, String> {
    let raw = fs::read_to_string(path)
        .map_err(|e| format!("无法读取环境文件 {}: {}", path.display(), e))?;
    let mut loaded = HashMap::new();
    for line_raw in raw.lines() {
        let line = line_raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((k, v)) = line.split_once('=') else {
            continue;
        };
        let key = k.trim().to_string();
        let val = v.trim().to_string();
        if key.is_empty() {
            continue;
        }
        loaded.insert(key.clone(), val.clone());
        if env::var_os(&key).is_none() {
            env::set_var(&key, &val);
        }
    }
    Ok(loaded)
}

impl Settings {
    pub fn from_env() -> Result<Self, String> {
        let schema = env::var("MYMONEY_DB_SCHEMA")
            .unwrap_or_else(|_| "mymoney_home".to_string())
            .trim()
            .to_string();
        validate_schema_name(&schema)?;

        let ttl_str = env::var("MYMONEY_PAIRING_TTL_SECONDS").unwrap_or_else(|_| "300".to_string());
        let pairing_ttl_seconds: i64 = ttl_str
            .trim()
            .parse()
            .map_err(|_| "MYMONEY_PAIRING_TTL_SECONDS 必须是整数，0 表示永久".to_string())?;
        if pairing_ttl_seconds < 0 {
            return Err("MYMONEY_PAIRING_TTL_SECONDS 不能为负数".to_string());
        }

        let database_url = env::var("MYMONEY_DATABASE_URL")
            .unwrap_or_default()
            .trim()
            .to_string();
        if database_url.is_empty() {
            return Err("请通过 MYMONEY_DATABASE_URL 配置数据库连接".to_string());
        }

        let backup_dir = env::var("MYMONEY_BACKUP_DIR")
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "/srv/mymoney-home/backups".to_string());

        let admin_token = env::var("MYMONEY_ADMIN_TOKEN").unwrap_or_default();
        let pairing_code = env::var("MYMONEY_PAIRING_CODE")
            .ok()
            .filter(|s| !s.is_empty());

        let port: u16 = env::var("MYMONEY_PORT")
            .ok()
            .and_then(|s| s.trim().parse().ok())
            .unwrap_or(8080);

        let base_url = env::var("MYMONEY_BASE_URL")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| format!("http://127.0.0.1:{}", port));

        let server_url = env::var("MYMONEY_SERVER_URL").unwrap_or_default();
        let ctrl_url = env::var("MYMONEY_CTRL_URL")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| "https://controlplane.tailscale.com".to_string());
        let tskey = env::var("MYMONEY_TSKEY").unwrap_or_default();

        Ok(Self {
            database_url,
            schema,
            backup_dir: PathBuf::from(backup_dir),
            admin_token,
            pairing_code,
            pairing_ttl_seconds,
            protocol_version: 1,
            max_push_items: 50,
            max_push_bytes: 512 * 1024,
            max_pull_items: 100,
            max_snapshot_items: 500,
            max_backup_bytes: 200 * 1024 * 1024,
            port,
            base_url,
            server_url,
            ctrl_url,
            tskey,
        })
    }
}
