use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use qrcode::render::svg;
use qrcode::{EcLevel, QrCode};
use serde::Serialize;

pub const QR_MAX_CHARS: usize = 4096;

#[derive(Serialize)]
struct ConnectCodePayload<'a> {
    v: u8,
    ctrl: &'a str,
    key: &'a str,
    server: &'a str,
    name: &'a str,
    code: &'a str,
}

/// 组装与 App `FamilyInvite` v2 对齐的 `MMJOIN2.` 完整连接码。
pub fn build_connect_code(
    ctrl_url: &str,
    auth_key: &str,
    server_url: &str,
    pair_code: &str,
    device_name: &str,
) -> Result<String, String> {
    let key = auth_key.trim();
    let pair = pair_code.trim();
    let server = server_url.trim().trim_end_matches('/');
    let ctrl = ctrl_url.trim();
    let name = device_name.trim();

    if !key.starts_with("tskey-") {
        return Err("网络授权码应以 tskey- 开头".to_string());
    }
    if pair.is_empty() {
        return Err("缺少业务配对码".to_string());
    }
    if server.is_empty() {
        return Err("缺少家庭服务地址".to_string());
    }

    let payload = ConnectCodePayload {
        v: 2,
        ctrl,
        key,
        server,
        name,
        code: pair,
    };
    let json_str =
        serde_json::to_string(&payload).map_err(|e| format!("序列化连接码失败: {}", e))?;
    let encoded = URL_SAFE_NO_PAD.encode(json_str.as_bytes());
    Ok(format!("MMJOIN2.{}", encoded))
}

/// 生成与复制串逐字一致的本地矢量二维码 SVG；超长或空串返回 None（不做任何静默截断）。
pub fn render_qr_svg(payload: &str) -> Option<String> {
    if payload.is_empty() || payload.len() > QR_MAX_CHARS {
        return None;
    }
    let code = QrCode::with_error_correction_level(payload.as_bytes(), EcLevel::M).ok()?;
    let svg_str = code
        .render::<svg::Color<'_>>()
        .quiet_zone(true)
        .dark_color(svg::Color("#000000"))
        .light_color(svg::Color("#ffffff"))
        .build();
    Some(svg_str)
}
