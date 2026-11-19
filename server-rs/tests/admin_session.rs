//! 管理台短期会话：管理密码 → 随机会话令牌（刷新免重输密码），可退出、过期即失效。

mod common;

use axum::http::{Method, StatusCode};
use serde_json::json;

use common::TestEnv;

#[tokio::test]
async fn admin_session_issue_use_logout_and_revoke() {
    let env = TestEnv::new().await;
    let master = env.admin_token.clone();

    // 未带令牌不能创建会话。
    let (status, _, _) = env
        .json_req(Method::POST, "/v1/admin/session", &[], None)
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    // 管理密码创建会话。
    let (status, _, body) = env
        .json_req(
            Method::POST,
            "/v1/admin/session",
            &[("x-admin-token", master.as_str())],
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    let session = body["sessionToken"].as_str().unwrap().to_string();
    assert!(session.len() >= 32);
    assert_eq!(body["expiresInSeconds"], 12 * 60 * 60);
    assert_ne!(session, master);

    // 会话令牌可以访问管理接口。
    let (status, _, _) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/snapshots?limit=1",
            &[("x-admin-token", session.as_str())],
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);

    // 会话令牌不能再创建新会话（只有管理密码可以）。
    let (status, _, _) = env
        .json_req(
            Method::POST,
            "/v1/admin/session",
            &[("x-admin-token", session.as_str())],
            None,
        )
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    // 退出后会话立即失效，管理密码本身不受影响。
    let (status, _, _) = env
        .json_req(
            Method::POST,
            "/v1/admin/session/logout",
            &[("x-admin-token", session.as_str())],
            Some(json!({})),
        )
        .await;
    assert_eq!(status, StatusCode::OK);

    let (status, _, _) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/snapshots?limit=1",
            &[("x-admin-token", session.as_str())],
            None,
        )
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    let (status, _, _) = env
        .json_req(
            Method::GET,
            "/v1/admin/dashboard/snapshots?limit=1",
            &[("x-admin-token", master.as_str())],
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);
}
