use axum::body::Body;
use axum::extract::Path;
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{Redirect, Response};
use rust_embed::RustEmbed;

#[derive(RustEmbed)]
#[folder = "../web/dist"]
struct DashboardAssets;

const STRICT_CSP: &str = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

pub async fn redirect_admin_root() -> Redirect {
    Redirect::temporary("/admin/cloud-backup")
}

pub async fn admin_page() -> Response {
    match DashboardAssets::get("index.html") {
        Some(content) => {
            let mut resp = Response::new(Body::from(content.data.into_owned()));
            *resp.status_mut() = StatusCode::OK;
            let h = resp.headers_mut();
            h.insert(
                header::CONTENT_TYPE,
                HeaderValue::from_static("text/html; charset=utf-8"),
            );
            h.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
            h.insert(header::PRAGMA, HeaderValue::from_static("no-cache"));
            h.insert("referrer-policy", HeaderValue::from_static("no-referrer"));
            h.insert(
                header::X_CONTENT_TYPE_OPTIONS,
                HeaderValue::from_static("nosniff"),
            );
            h.insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("DENY"));
            h.insert(
                header::CONTENT_SECURITY_POLICY,
                HeaderValue::from_static(STRICT_CSP),
            );
            resp
        }
        None => Response::builder()
            .status(StatusCode::NOT_FOUND)
            .body(Body::from("dashboard assets missing"))
            .unwrap(),
    }
}

fn serve_embedded_file(name: &str, cache_control: &'static str) -> Response {
    match DashboardAssets::get(name) {
        Some(content) => {
            let mime = mime_guess::from_path(name)
                .first_or_octet_stream()
                .essence_str()
                .to_string();
            let mut resp = Response::new(Body::from(content.data.into_owned()));
            *resp.status_mut() = StatusCode::OK;
            let h = resp.headers_mut();
            if let Ok(ct) = HeaderValue::from_str(&mime) {
                h.insert(header::CONTENT_TYPE, ct);
            }
            h.insert(
                header::CACHE_CONTROL,
                HeaderValue::from_static(cache_control),
            );
            h.insert(
                header::X_CONTENT_TYPE_OPTIONS,
                HeaderValue::from_static("nosniff"),
            );
            h.insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("DENY"));
            h.insert("referrer-policy", HeaderValue::from_static("no-referrer"));
            resp
        }
        None => Response::builder()
            .status(StatusCode::NOT_FOUND)
            .body(Body::from("icon not found"))
            .unwrap(),
    }
}

pub async fn favicon() -> Response {
    serve_embedded_file("favicon.png", "private, max-age=86400")
}

pub async fn apple_touch_icon() -> Response {
    serve_embedded_file("apple-touch-icon.png", "private, max-age=86400")
}

pub async fn serve_asset(Path(rel_path): Path<String>) -> Response {
    let clean = rel_path.trim_start_matches('/');
    let key = format!("assets/{}", clean);
    let asset = DashboardAssets::get(&key).or_else(|| DashboardAssets::get(clean));
    match asset {
        Some(content) => {
            let mime = mime_guess::from_path(clean)
                .first_or_octet_stream()
                .essence_str()
                .to_string();
            let mut resp = Response::new(Body::from(content.data.into_owned()));
            *resp.status_mut() = StatusCode::OK;
            let h = resp.headers_mut();
            if let Ok(ct) = HeaderValue::from_str(&mime) {
                h.insert(header::CONTENT_TYPE, ct);
            }
            h.insert(
                header::CACHE_CONTROL,
                HeaderValue::from_static("private, max-age=31536000, immutable"),
            );
            h.insert(
                header::X_CONTENT_TYPE_OPTIONS,
                HeaderValue::from_static("nosniff"),
            );
            h.insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("DENY"));
            h.insert("referrer-policy", HeaderValue::from_static("no-referrer"));
            resp
        }
        None => Response::builder()
            .status(StatusCode::NOT_FOUND)
            .body(Body::from("asset not found"))
            .unwrap(),
    }
}
