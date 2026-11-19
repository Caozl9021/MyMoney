use axum::extract::rejection::JsonRejection;
use axum::extract::{FromRequest, Request};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::de::DeserializeOwned;
use serde_json::json;

#[derive(Debug)]
pub struct AppError {
    pub status: StatusCode,
    pub message: String,
}

impl AppError {
    pub fn new(status: StatusCode, message: impl Into<String>) -> Self {
        Self {
            status,
            message: message.into(),
        }
    }

    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_REQUEST, message)
    }

    pub fn unauthorized(message: impl Into<String>) -> Self {
        Self::new(StatusCode::UNAUTHORIZED, message)
    }

    pub fn forbidden(message: impl Into<String>) -> Self {
        Self::new(StatusCode::FORBIDDEN, message)
    }

    pub fn not_found(message: impl Into<String>) -> Self {
        Self::new(StatusCode::NOT_FOUND, message)
    }

    pub fn conflict(message: impl Into<String>) -> Self {
        Self::new(StatusCode::CONFLICT, message)
    }

    pub fn gone(message: impl Into<String>) -> Self {
        Self::new(StatusCode::GONE, message)
    }

    pub fn length_required(message: impl Into<String>) -> Self {
        Self::new(StatusCode::LENGTH_REQUIRED, message)
    }

    pub fn payload_too_large(message: impl Into<String>) -> Self {
        Self::new(StatusCode::PAYLOAD_TOO_LARGE, message)
    }

    pub fn unsupported_media_type(message: impl Into<String>) -> Self {
        Self::new(StatusCode::UNSUPPORTED_MEDIA_TYPE, message)
    }

    pub fn unprocessable(message: impl Into<String>) -> Self {
        Self::new(StatusCode::UNPROCESSABLE_ENTITY, message)
    }

    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, message)
    }

    pub fn insufficient_storage(message: impl Into<String>) -> Self {
        Self::new(StatusCode::INSUFFICIENT_STORAGE, message)
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        (self.status, Json(json!({ "error": self.message }))).into_response()
    }
}

impl From<sqlx::Error> for AppError {
    fn from(err: sqlx::Error) -> Self {
        tracing::error!(error = %err, "database operation failed");
        Self::internal("数据库操作失败")
    }
}

/// 自定义 JSON 提取器：将 Axum 默认纯文本解析/反序列化错误统一转为 422 JSON `{"error": ...}`。
pub struct ApiJson<T>(pub T);

impl<S, T> FromRequest<S> for ApiJson<T>
where
    T: DeserializeOwned,
    S: Send + Sync,
{
    type Rejection = AppError;

    async fn from_request(req: Request, state: &S) -> Result<Self, Self::Rejection> {
        match Json::<T>::from_request(req, state).await {
            Ok(Json(value)) => Ok(ApiJson(value)),
            Err(JsonRejection::JsonDataError(err)) => Err(AppError::unprocessable(format!(
                "请求参数无效: {}",
                err.body_text()
            ))),
            Err(JsonRejection::JsonSyntaxError(_)) => {
                Err(AppError::unprocessable("请求体不是合法的 JSON"))
            }
            Err(JsonRejection::MissingJsonContentType(_)) => {
                Err(AppError::unprocessable("请求必须使用 application/json"))
            }
            Err(_) => Err(AppError::unprocessable("请求参数解析失败")),
        }
    }
}

/// 生成与 Python `json.dumps(obj, sort_keys=True)` 完全一致的规范化 JSON（含 `", "` / `": "` 分隔符与 `\uXXXX` ASCII 转义），
/// 确保 `device_enrollment_operations` 与 `backup_delete_operations` 的 `request_hash` 在跨语言切换期间无缝幂等。
pub fn python_json_dumps_sorted_ascii(value: &serde_json::Value) -> String {
    fn write_escaped_str(out: &mut String, s: &str) {
        out.push('"');
        for ch in s.chars() {
            match ch {
                '"' => out.push_str("\\\""),
                '\\' => out.push_str("\\\\"),
                '\x08' => out.push_str("\\b"),
                '\x0c' => out.push_str("\\f"),
                '\n' => out.push_str("\\n"),
                '\r' => out.push_str("\\r"),
                '\t' => out.push_str("\\t"),
                c if (c as u32) < 0x20 => {
                    out.push_str(&format!("\\u{:04x}", c as u32));
                }
                c if c.is_ascii() => out.push(c),
                c => {
                    let mut buf = [0u16; 2];
                    for unit in c.encode_utf16(&mut buf) {
                        out.push_str(&format!("\\u{:04x}", unit));
                    }
                }
            }
        }
        out.push('"');
    }

    fn write_val(out: &mut String, value: &serde_json::Value) {
        match value {
            serde_json::Value::Null => out.push_str("null"),
            serde_json::Value::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
            serde_json::Value::Number(n) => out.push_str(&n.to_string()),
            serde_json::Value::String(s) => write_escaped_str(out, s),
            serde_json::Value::Array(arr) => {
                out.push('[');
                for (i, item) in arr.iter().enumerate() {
                    if i > 0 {
                        out.push_str(", ");
                    }
                    write_val(out, item);
                }
                out.push(']');
            }
            serde_json::Value::Object(map) => {
                out.push('{');
                let mut keys: Vec<&String> = map.keys().collect();
                keys.sort();
                for (i, k) in keys.iter().enumerate() {
                    if i > 0 {
                        out.push_str(", ");
                    }
                    write_escaped_str(out, k);
                    out.push_str(": ");
                    write_val(out, &map[*k]);
                }
                out.push('}');
            }
        }
    }

    let mut out = String::new();
    write_val(&mut out, value);
    out
}

/// 生成与 Python `json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))` 完全一致的规范化 JSON，
/// 用于 `sync_operations` 与 `ledger_operations` 幂等 `request_hash`。
pub fn python_json_dumps_sorted_compact(value: &serde_json::Value) -> String {
    fn write_escaped_str(out: &mut String, s: &str) {
        out.push('"');
        for ch in s.chars() {
            match ch {
                '"' => out.push_str("\\\""),
                '\\' => out.push_str("\\\\"),
                '\x08' => out.push_str("\\b"),
                '\x0c' => out.push_str("\\f"),
                '\n' => out.push_str("\\n"),
                '\r' => out.push_str("\\r"),
                '\t' => out.push_str("\\t"),
                c if (c as u32) < 0x20 => {
                    out.push_str(&format!("\\u{:04x}", c as u32));
                }
                c => out.push(c),
            }
        }
        out.push('"');
    }

    fn write_val(out: &mut String, value: &serde_json::Value) {
        match value {
            serde_json::Value::Null => out.push_str("null"),
            serde_json::Value::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
            serde_json::Value::Number(n) => out.push_str(&n.to_string()),
            serde_json::Value::String(s) => write_escaped_str(out, s),
            serde_json::Value::Array(arr) => {
                out.push('[');
                for (i, item) in arr.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    write_val(out, item);
                }
                out.push(']');
            }
            serde_json::Value::Object(map) => {
                out.push('{');
                let mut keys: Vec<&String> = map.keys().collect();
                keys.sort();
                for (i, k) in keys.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    write_escaped_str(out, k);
                    out.push(':');
                    write_val(out, &map[*k]);
                }
                out.push('}');
            }
        }
    }

    let mut out = String::new();
    write_val(&mut out, value);
    out
}
