use std::sync::Arc;

use axum::extract::{DefaultBodyLimit, Request};
use axum::http::{header, HeaderValue};
use axum::middleware::{self, Next};
use axum::response::Response;
use axum::routing::{get, post, put};
use axum::Router;
use sqlx::PgPool;

use crate::admin::{routes as admin_routes, static_ui};
use crate::analytics::snapshot::SnapshotCache;
use crate::auth::AdminSessions;
use crate::backup::{enrollment, storage};
use crate::config::Settings;
use crate::pairing::PairingStore;
use crate::sync::{ledger, rules};

#[derive(Clone)]
pub struct AppState {
    pub settings: Arc<Settings>,
    pub pool: PgPool,
    pub pairing: Arc<PairingStore>,
    pub snapshot_cache: Arc<SnapshotCache>,
    pub admin_sessions: Arc<AdminSessions>,
}

impl AppState {
    pub fn new(settings: Settings, pool: PgPool) -> Self {
        let pairing = Arc::new(PairingStore::new(
            settings.pairing_code.clone(),
            settings.pairing_ttl_seconds,
        ));
        if settings.pairing_code.is_some() {
            pairing.issue(None);
        }
        Self {
            settings: Arc::new(settings),
            pool,
            pairing,
            snapshot_cache: Arc::new(SnapshotCache::new()),
            admin_sessions: Arc::new(AdminSessions::new()),
        }
    }
}

async fn security_headers_middleware(req: Request, next: Next) -> Response {
    let path = req.uri().path().to_string();
    let is_asset = path.starts_with("/admin/cloud-backup/assets/");
    let is_private_api = path.starts_with("/v1/")
        || path == "/admin"
        || path == "/admin/cloud-backup"
        || path == "/admin/cloud-backup/";

    let mut resp = next.run(req).await;
    let headers = resp.headers_mut();
    if is_private_api && !is_asset {
        headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
        headers.insert(header::PRAGMA, HeaderValue::from_static("no-cache"));
    }
    if path.starts_with("/v1/") || path.starts_with("/admin") {
        headers
            .entry(header::X_CONTENT_TYPE_OPTIONS)
            .or_insert_with(|| HeaderValue::from_static("nosniff"));
        headers
            .entry(header::X_FRAME_OPTIONS)
            .or_insert_with(|| HeaderValue::from_static("DENY"));
        headers
            .entry("referrer-policy")
            .or_insert_with(|| HeaderValue::from_static("no-referrer"));
    }
    resp
}

pub fn build_router(state: AppState) -> Router {
    let upload_route = Router::new()
        .route("/v1/backups/{backup_id}", put(storage::upload_backup))
        .layer(DefaultBodyLimit::disable());

    Router::new()
        .route("/health", get(rules::health))
        .route("/v1/admin/pair-code", post(rules::admin_pair_code))
        .route("/v1/pair", post(rules::pair))
        .route("/v1/sync/snapshot", get(rules::sync_snapshot))
        .route("/v1/sync/push", post(rules::sync_push))
        .route("/v1/sync/pull", get(rules::sync_pull))
        .route("/v1/sync/ack", post(rules::sync_ack))
        .route("/v1/device/revoke", post(rules::revoke_device))
        // W2/W3 账本同步
        .route("/v1/ledger/snapshot", get(ledger::snapshot))
        .route("/v1/ledger/push", post(ledger::push))
        .route("/v1/ledger/pull", get(ledger::pull))
        .route("/v1/ledger/ack", post(ledger::ack))
        .route("/v1/ledger/import/sources", get(ledger::import_sources))
        .route("/v1/ledger/import/source", get(ledger::import_source))
        // X/AA 身份与能力
        .route("/v1/backup-capabilities", get(enrollment::capabilities))
        .route("/v1/backup-context", get(enrollment::backup_context))
        .route(
            "/v1/device-enrollment/authorize",
            post(enrollment::authorize),
        )
        .route("/v1/device-enrollment/sources", get(enrollment::sources))
        .route("/v1/device-enrollment/complete", post(enrollment::complete))
        .route("/v1/devices/self", get(enrollment::self_device))
        .route(
            "/v1/devices/self/bind-installation",
            post(enrollment::bind_installation),
        )
        .route("/v1/devices/self/rename", post(enrollment::rename))
        // 个人云备份
        .merge(upload_route)
        .route("/v1/backups", get(storage::list_backups))
        .route("/v1/backups/{backup_id}", get(storage::download_backup))
        .route(
            "/v1/backups/{backup_id}/status",
            get(storage::backup_status),
        )
        .route("/v1/backups/delete", post(storage::delete_backups))
        // Z 既有管理员路由
        .route(
            "/v1/admin/backup-settings",
            get(admin_routes::backup_settings),
        )
        .route("/v1/admin/session", post(admin_routes::create_session))
        .route(
            "/v1/admin/session/logout",
            post(admin_routes::logout_session),
        )
        .route("/v1/admin/connect-code", post(admin_routes::connect_code))
        .route(
            "/v1/admin/connect-codes/{invitation_id}",
            get(admin_routes::connect_code_status),
        )
        .route(
            "/v1/admin/connect-codes/{invitation_id}/cancel",
            post(admin_routes::connect_code_cancel),
        )
        .route(
            "/v1/admin/backup-overview",
            get(admin_routes::backup_overview),
        )
        .route(
            "/v1/admin/backup-sources",
            get(admin_routes::backup_sources),
        )
        .route(
            "/v1/admin/backup-sources/{device_id}/archive",
            post(admin_routes::archive_source),
        )
        // 新增财务仪表盘与运维增强 API
        .route("/v1/admin/backups", get(admin_routes::admin_backups_list))
        .route(
            "/v1/admin/backups/{backup_id}/download",
            get(admin_routes::admin_backup_download),
        )
        .route(
            "/v1/admin/storage-audit",
            get(admin_routes::admin_storage_audit),
        )
        .route(
            "/v1/admin/dashboard/snapshots",
            get(admin_routes::admin_backups_list),
        )
        .route(
            "/v1/admin/dashboard/overview",
            get(admin_routes::dashboard_overview),
        )
        .route(
            "/v1/admin/dashboard/sync-head",
            get(admin_routes::dashboard_sync_head),
        )
        .route(
            "/v1/admin/dashboard/analytics",
            get(admin_routes::dashboard_analytics),
        )
        .route(
            "/v1/admin/dashboard/bills",
            get(admin_routes::dashboard_bills),
        )
        .route(
            "/v1/admin/dashboard/bills/export",
            get(admin_routes::dashboard_bills_export),
        )
        .route(
            "/v1/admin/dashboard/assets",
            get(admin_routes::dashboard_assets),
        )
        .route(
            "/v1/admin/dashboard/audit",
            get(admin_routes::dashboard_audit),
        )
        .route(
            "/v1/admin/dashboard/diff",
            get(admin_routes::dashboard_diff),
        )
        // Web UI 托管与跳转
        .route("/admin", get(static_ui::redirect_admin_root))
        .route("/admin/cloud-backup", get(static_ui::admin_page))
        .route("/admin/cloud-backup/", get(static_ui::admin_page))
        .route("/admin/cloud-backup/favicon.png", get(static_ui::favicon))
        .route(
            "/admin/cloud-backup/apple-touch-icon.png",
            get(static_ui::apple_touch_icon),
        )
        .route(
            "/admin/cloud-backup/assets/{*path}",
            get(static_ui::serve_asset),
        )
        .layer(middleware::from_fn(security_headers_middleware))
        .with_state(state)
}
