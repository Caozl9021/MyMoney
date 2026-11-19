// 各集成测试共用的辅助设施；单个测试二进制不一定用到全部字段/方法。
#![allow(dead_code)]

use std::path::PathBuf;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode};
use axum::Router;
use http_body_util::BodyExt;
use serde_json::Value;
use sqlx::{Executor, PgPool};
use tempfile::TempDir;
use tower::ServiceExt;
use uuid::Uuid;

use mymoney_home::config::Settings;
use mymoney_home::db::{apply_schema, connect_pool};
use mymoney_home::state::{build_router, AppState};

pub struct TestEnv {
    pub schema: String,
    pub database_url: String,
    pub pool: PgPool,
    pub backup_dir: TempDir,
    pub router: Router,
    pub state: AppState,
    pub admin_token: String,
}

impl TestEnv {
    pub async fn new() -> Self {
        let database_url = std::env::var("MYMONEY_TEST_DATABASE_URL")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| "postgresql://mymoney@127.0.0.1:5432/mymoney".to_string());
        let schema = format!("mymoney_rs_test_{}", Uuid::new_v4().simple());
        assert!(schema.starts_with("mymoney_rs_test_"));

        let backup_dir = TempDir::new().expect("create temp backup dir");
        let admin_token = "admin-test-only".to_string();

        let bootstrap_pool = connect_pool(&database_url, "public")
            .await
            .expect("connect test postgres");
        bootstrap_pool
            .execute(format!("DROP SCHEMA IF EXISTS \"{}\" CASCADE", schema).as_str())
            .await
            .expect("drop pre schema");
        bootstrap_pool
            .execute(format!("CREATE SCHEMA \"{}\"", schema).as_str())
            .await
            .expect("create schema");
        bootstrap_pool.close().await;

        let pool = connect_pool(&database_url, &schema)
            .await
            .expect("connect isolated schema pool");
        apply_schema(&pool, &schema).await.expect("apply schema");

        let settings = Settings {
            database_url: database_url.clone(),
            schema: schema.clone(),
            backup_dir: PathBuf::from(backup_dir.path()),
            admin_token: admin_token.clone(),
            pairing_code: None,
            pairing_ttl_seconds: 300,
            protocol_version: 1,
            max_push_items: 50,
            max_push_bytes: 512 * 1024,
            max_pull_items: 100,
            max_snapshot_items: 500,
            max_backup_bytes: 200 * 1024 * 1024,
            port: 18080,
            base_url: "http://127.0.0.1:18080".to_string(),
            server_url: "http://100.65.248.62:18080".to_string(),
            ctrl_url: "https://controlplane.tailscale.com".to_string(),
            tskey: "tskey-auth-test-network-secret".to_string(),
        };

        let state = AppState::new(settings, pool.clone());
        let router = build_router(state.clone());

        Self {
            schema,
            database_url,
            pool,
            backup_dir,
            router,
            state,
            admin_token,
        }
    }

    pub async fn cleanup(self) {
        let schema = self.schema.clone();
        let url = self.database_url.clone();
        self.pool.close().await;
        if let Ok(p) = connect_pool(&url, "public").await {
            let _ = p
                .execute(format!("DROP SCHEMA IF EXISTS \"{}\" CASCADE", schema).as_str())
                .await;
            p.close().await;
        }
    }

    pub async fn send(&self, req: Request<Body>) -> (StatusCode, axum::http::HeaderMap, Vec<u8>) {
        let resp = self
            .router
            .clone()
            .oneshot(req)
            .await
            .expect("router oneshot");
        let status = resp.status();
        let headers = resp.headers().clone();
        let body = resp
            .into_body()
            .collect()
            .await
            .expect("collect body")
            .to_bytes()
            .to_vec();
        (status, headers, body)
    }

    pub async fn json_req(
        &self,
        method: Method,
        uri: &str,
        headers: &[(&str, &str)],
        body: Option<Value>,
    ) -> (StatusCode, axum::http::HeaderMap, Value) {
        let mut builder = Request::builder().method(method).uri(uri);
        for (k, v) in headers {
            builder = builder.header(*k, *v);
        }
        let req = match body {
            Some(val) => builder
                .header("content-type", "application/json")
                .body(Body::from(serde_json::to_vec(&val).unwrap()))
                .unwrap(),
            None => builder.body(Body::empty()).unwrap(),
        };
        let (status, hdrs, bytes) = self.send(req).await;
        let parsed = serde_json::from_slice(&bytes)
            .unwrap_or_else(|_| Value::String(String::from_utf8_lossy(&bytes).to_string()));
        (status, hdrs, parsed)
    }
}
