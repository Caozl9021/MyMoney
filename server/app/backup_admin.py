"""Small, token-protected personal backup administration surface.

No credentials are embedded in HTML, URLs, cookies, browser storage or logs.
Opening this page does not issue a code or mutate device records.
"""
from __future__ import annotations

import io
import os
import secrets

import qrcode
from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field
from qrcode.image.svg import SvgPathImage

from .config import get_settings
from .connect_code import build_connect_code
from .db import connection

router = APIRouter()

# A full MMJOIN2 code (ctrl+tskey+server+pair code) is ~300 chars initially, more if an
# operator types a long server address. M-level ECC keeps the module grid printable while
# tolerating a camera at arm's length. Same overall limit the App enforces on paste.
QR_MAX_CHARS = 4096


def render_qr_svg(payload: str) -> str | None:
    """Inline SVG for the exact connect-code string, or None when it cannot be encoded.

    Encodes the paste string verbatim (see Z3.3): the scanned bytes decode to exactly the
    same MMJOIN2 code the copy button yields, so scanning and pasting are one path.
    """
    if not payload or len(payload) > QR_MAX_CHARS:
        return None
    try:
        qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=10, border=4)
        qr.add_data(payload)
        qr.make(fit=True)
    except Exception:  # capacity/structure error -> let the operator copy instead
        return None
    buffer = io.BytesIO()
    qr.make_image(image_factory=SvgPathImage).save(buffer)
    svg = buffer.getvalue()
    return svg.decode("utf-8") if isinstance(svg, (bytes, bytearray)) else str(svg)


def administrator(x_admin_token: str = Header(default="")) -> None:
    expected = get_settings().admin_token
    if not expected or not secrets.compare_digest(expected, x_admin_token):
        raise HTTPException(403, "管理令牌无效")


class ConnectCodeRequest(BaseModel):
    serverUrl: str = Field(default="", max_length=1024)
    authKey: str = Field(default="", max_length=512)
    ctrlUrl: str = Field(default="", max_length=1024)
    deviceName: str = Field(default="", max_length=80)


@router.get("/v1/admin/backup-settings", dependencies=[Depends(administrator)])
def backup_settings() -> dict:
    return {
        "serverUrl": os.getenv("MYMONEY_SERVER_URL", ""),
        "ctrlUrl": os.getenv("MYMONEY_CTRL_URL", "https://controlplane.tailscale.com"),
        "networkKeyConfigured": bool(os.getenv("MYMONEY_TSKEY")),
    }


@router.post("/v1/admin/connect-code", dependencies=[Depends(administrator)])
def connect_code(body: ConnectCodeRequest) -> dict:
    from .device_enrollment import issue_connection_code

    server = body.serverUrl.strip() or os.getenv("MYMONEY_SERVER_URL", "")
    network_key = body.authKey.strip() or os.getenv("MYMONEY_TSKEY", "")
    ctrl = body.ctrlUrl.strip() or os.getenv("MYMONEY_CTRL_URL", "https://controlplane.tailscale.com")
    # Validate the complete configuration before issuing any business authorization.
    try:
        build_connect_code(ctrl_url=ctrl, auth_key=network_key, server_url=server,
                           pair_code="validation", device_name=body.deviceName)
    except ValueError as exc:
        raise HTTPException(422, str(exc)) from exc
    with connection() as conn:
        issued = issue_connection_code(conn, ttl_seconds=300)
    code = build_connect_code(ctrl_url=ctrl, auth_key=network_key, server_url=server,
                              pair_code=issued["code"], device_name=body.deviceName)
    # Render the exact paste string; a QR that cannot hold the full code is omitted and the
    # operator copies instead (never a silently truncated / re-signed code).
    return {
        "connectCode": code,
        "expiresInSeconds": issued["expiresInSeconds"],
        "oneTime": True,
        "invitationId": issued["invitationId"],
        "expiresAt": issued["expiresAt"],
        "serverTime": issued["serverTime"],
        "qrSvg": render_qr_svg(code),
        "qrEncodable": render_qr_svg(code) is not None,
    }


class CancelRequest(BaseModel):
    pass


@router.get("/v1/admin/connect-codes/{invitation_id}", dependencies=[Depends(administrator)])
def connect_code_status(invitation_id: str) -> dict:
    """Invitation lifecycle status only. Never returns the key, full code, grant or credential."""
    from uuid import UUID

    from .device_enrollment import invitation_status

    try:
        target = UUID(invitation_id)
    except ValueError as exc:
        raise HTTPException(422, "邀请标识无效") from exc
    with connection() as conn:
        return invitation_status(conn, target)


@router.post("/v1/admin/connect-codes/{invitation_id}/cancel", dependencies=[Depends(administrator)])
def connect_code_cancel(invitation_id: str) -> dict:
    """Cancel this invitation's not-yet-completed business authorization only.

    Does not revoke an already-completed device, and never touches the Tailscale key.
    """
    from uuid import UUID

    from .device_enrollment import cancel_invitation

    try:
        target = UUID(invitation_id)
    except ValueError as exc:
        raise HTTPException(422, "邀请标识无效") from exc
    with connection() as conn:
        return cancel_invitation(conn, target)


@router.get("/v1/admin/backup-overview", dependencies=[Depends(administrator)])
def backup_overview() -> dict:
    with connection() as conn:
        row = conn.execute("""
            SELECT
              (SELECT count(*) FROM devices d WHERE d.revoked_at IS NULL) AS active_devices,
              (SELECT count(*) FROM ledger_backups b WHERE b.deleted_at IS NULL) AS live_backups,
              (SELECT coalesce(sum(b.size_bytes),0) FROM ledger_backups b WHERE b.deleted_at IS NULL) AS live_bytes,
              (SELECT max(b.uploaded_at) FROM ledger_backups b WHERE b.deleted_at IS NULL) AS last_backup_at
            """).fetchone()
        recent = conn.execute("""SELECT b.id, b.device_id, d.name AS device_name, b.bill_count,
                       b.size_bytes, b.uploaded_at, b.snapshot_at, b.app_version, b.backup_schema
                FROM ledger_backups b JOIN devices d ON d.space_id=b.space_id AND d.id=b.device_id
                WHERE b.deleted_at IS NULL ORDER BY b.uploaded_at DESC, b.id DESC LIMIT 10""").fetchall()
    return {
        "activeDevices": row["active_devices"],
        "liveBackups": row["live_backups"],
        "liveBytes": int(row["live_bytes"]),
        "lastBackupAt": row["last_backup_at"].isoformat() if row["last_backup_at"] else None,
        "recentBackups": [{
            "backupId": str(r["id"]), "deviceId": str(r["device_id"]), "deviceName": r["device_name"],
            "billCount": r["bill_count"], "sizeBytes": r["size_bytes"],
            "uploadedAt": r["uploaded_at"].isoformat(),
            "snapshotAt": r["snapshot_at"].isoformat(),
            "appVersion": r["app_version"], "backupSchema": r["backup_schema"],
        } for r in recent],
        "serverTime": __import__("datetime").datetime.now(__import__("datetime").timezone.utc).isoformat(),
    }


@router.get("/v1/admin/backup-sources", dependencies=[Depends(administrator)])
def backup_sources() -> dict:
    with connection() as conn:
        rows = conn.execute("""SELECT d.id, d.name, d.created_at, d.last_seen_at,
                    d.revoked_at, d.archived_at,
                    (SELECT count(*) FROM ledger_backups b
                     WHERE b.space_id=d.space_id AND b.device_id=d.id
                       AND b.deleted_at IS NULL) AS backup_count,
                    (SELECT max(b.uploaded_at) FROM ledger_backups b
                     WHERE b.space_id=d.space_id AND b.device_id=d.id
                       AND b.deleted_at IS NULL) AS last_backup_at,
                    (SELECT count(*) FROM ledger_changes l
                     WHERE l.space_id=d.space_id AND l.device_id=d.id) AS ledger_change_count,
                    (SELECT count(*) FROM rule_items i
                     WHERE i.space_id=d.space_id AND i.updated_by=d.id) AS rule_count
                FROM devices d ORDER BY d.created_at DESC""").fetchall()
    return {"items": [{
        "deviceId": str(row["id"]), "name": row["name"],
        "createdAt": row["created_at"].isoformat(),
        "lastSeenAt": row["last_seen_at"].isoformat() if row["last_seen_at"] else None,
        "lastBackupAt": row["last_backup_at"].isoformat() if row["last_backup_at"] else None,
        "backupCount": row["backup_count"], "ledgerChangeCount": row["ledger_change_count"],
        "ruleCount": row["rule_count"], "archived": row["archived_at"] is not None,
        "revoked": row["revoked_at"] is not None,
    } for row in rows]}


class ArchiveRequest(BaseModel):
    archived: bool


@router.post("/v1/admin/backup-sources/{device_id}/archive", dependencies=[Depends(administrator)])
def archive_source(device_id: str, body: ArchiveRequest) -> dict:
    from uuid import UUID
    try:
        source = UUID(device_id)
    except ValueError as exc:
        raise HTTPException(422, "设备标识无效") from exc
    with connection() as conn:
        row = conn.execute("""UPDATE devices SET archived_at=CASE WHEN %s THEN now() ELSE NULL END
                              WHERE id=%s RETURNING id""", (body.archived, source)).fetchone()
        if not row:
            raise HTTPException(404, "设备不存在")
        conn.commit()
    return {"archived": body.archived}


PAGE = r"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>麦米 · 云备份管理</title>
<style>
:root{--bg:#f8fafc;--surface:#fff;--primary:#4f46e5;--primary-soft:#eef2ff;--title:#1f2937;--body:#4b5563;--muted:#6b7280;--line:#e5e7eb;--radius:24px;--radius-sm:16px}
*{box-sizing:border-box}
html,body{margin:0}
body{font:16px/1.6 -apple-system,"PingFang SC","Microsoft YaHei","Noto Sans CJK SC",sans-serif;background:var(--bg);color:var(--body)}
a{color:var(--primary);text-decoration:none}
.wrap{max-width:1120px;margin:0 auto;padding:56px 24px}
h1{font-size:30px;color:var(--title);margin:0 0 6px}
h2{font-size:20px;color:var(--title);margin:0 0 12px}
p.sub{color:var(--muted);margin:0 0 28px}
.card{background:var(--surface);border-radius:var(--radius);box-shadow:0 18px 40px -20px rgba(79,70,229,.18),0 8px 20px -14px rgba(15,23,42,.12);padding:28px 30px;margin:0 0 26px;transition:transform .3s ease,box-shadow .3s ease}
label{display:block;margin:14px 0 6px;font-size:14px;color:var(--title);font-weight:500}
input,textarea{width:100%;padding:13px 16px;background:#f9fafb;border:1px solid transparent;border-radius:var(--radius-sm);font:inherit;color:var(--title);transition:all .2s ease}
input:focus,textarea:focus{outline:none;background:#fff;border-color:rgba(79,70,229,.35);box-shadow:0 0 0 3px rgba(79,70,229,.15)}
textarea{min-height:120px;word-break:break-all;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px}
button{border:0;border-radius:var(--radius-sm);padding:12px 22px;font:inherit;font-weight:500;cursor:pointer;transition:transform .2s ease,box-shadow .2s ease,background .2s ease;margin:10px 10px 0 0}
button.primary{background:var(--primary);color:#fff}
button.primary:hover{transform:translateY(-2px);box-shadow:0 12px 24px -10px rgba(79,70,229,.55)}
button.primary:active{transform:translateY(0)}
button.ghost{background:var(--primary-soft);color:var(--primary)}
button.quiet{background:#f3f4f6;color:var(--body)}
button.danger{background:#fef2f2;color:#b91c1c}
button:disabled{opacity:.45;cursor:not-allowed;transform:none;box-shadow:none}
nav{display:flex;gap:8px;flex-wrap:wrap;margin:0 0 26px}
nav a{padding:10px 18px;border-radius:var(--radius-sm);color:var(--body);background:var(--surface);box-shadow:0 4px 12px -8px rgba(15,23,42,.2)}
nav a.on{background:var(--primary);color:#fff}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:20px}
.stat{background:var(--surface);border-radius:var(--radius);padding:22px 24px;box-shadow:0 14px 32px -22px rgba(15,23,42,.25)}
.stat .k{font-size:13px;color:var(--muted);margin-bottom:8px}
.stat .v{font-size:28px;font-weight:600;color:var(--title);letter-spacing:-.5px}
.row{display:flex;gap:18px;flex-wrap:wrap;align-items:flex-start}
.qr{background:#fff;border-radius:var(--radius-sm);padding:16px;display:inline-block;line-height:0}
.qr svg{width:240px;height:240px;display:block}
table{width:100%;border-collapse:collapse}
th,td{text-align:left;padding:12px 10px;border-bottom:1px solid var(--line);font-size:14px}
th{color:var(--muted);font-weight:500}
td .mono{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;color:var(--muted)}
.pill{display:inline-block;padding:2px 10px;border-radius:999px;font-size:12px;background:var(--primary-soft);color:var(--primary)}
.pill.warn{background:#fef3c7;color:#92400e}
.pill.ok{background:#ecfdf5;color:#047857}
.pill.bad{background:#fef2f2;color:#b91c1c}
.muted{color:var(--muted);font-size:13px}
#status{position:fixed;left:50%;bottom:26px;transform:translateX(-50%);background:rgba(31,41,55,.94);color:#fff;padding:12px 22px;border-radius:999px;font-size:14px;opacity:0;pointer-events:none;transition:opacity .2s}
#status.show{opacity:1}
[hidden]{display:none!important}
@media (max-width:640px){.wrap{padding:32px 16px}.card{padding:22px}nav{overflow-x:auto}.row{flex-direction:column}}
@media (prefers-reduced-motion:reduce){*{transition:none!important}}
</style></head>
<body><div class="wrap">
<h1>麦米 · 云备份管理</h1>
<p class="sub">只读的设备与备份汇总、连接新设备与邀请状态。管理令牌仅存于本页内存，不写入浏览器存储。</p>

<section id="lock" class="card" style="max-width:460px">
  <h2>解锁管理</h2>
  <label>管理令牌</label>
  <input id="admin" type="password" autocomplete="off" placeholder="输入服务器管理令牌">
  <button class="primary" id="login">解锁</button>
  <p class="muted">令牌仅用于本页请求，不保存。离开或锁定会清除。</p>
</section>

<div id="app" hidden>
  <nav>
    <a href="#" data-view="overview" class="on">概览</a>
    <a href="#" data-view="devices">设备与备份</a>
    <a href="#" data-view="connect">连接新设备</a>
    <a href="#" data-view="settings">服务设置</a>
    <a href="#" id="locklink" style="margin-left:auto">锁定</a>
  </nav>

  <section class="view" id="view-overview">
    <div class="grid" id="stats"></div>
    <div class="card" style="margin-top:26px">
      <div class="row" style="justify-content:space-between;align-items:center">
        <h2 style="margin:0">最近备份活动</h2>
        <button class="ghost" id="ov-refresh">刷新</button>
      </div>
      <table id="ov-recent"><thead><tr><th>设备</th><th>接收时间</th><th>笔数</th><th>大小</th></tr></thead><tbody></tbody></table>
      <p class="muted" id="ov-time"></p>
    </div>
  </section>

  <section class="view" id="view-devices" hidden>
    <div class="card">
      <div class="row" style="justify-content:space-between;align-items:center">
        <h2 style="margin:0">设备与备份</h2>
        <button class="ghost" id="dev-refresh">刷新</button>
      </div>
      <label>按名称或短ID搜索</label>
      <input id="dev-q" placeholder="输入名称或ID前几位">
      <table id="dev-table"><thead><tr><th>设备</th><th>备份</th><th>最近访问</th><th>状态</th><th></th></tr></thead><tbody></tbody></table>
    </div>
  </section>

  <section class="view" id="view-connect" hidden>
    <div class="card">
      <h2>连接新设备</h2>
      <label>设备标签（建议名称，非绑定身份）</label>
      <input id="c-name" placeholder="例如 我的小米手机">
      <label>服务地址</label>
      <input id="c-server" placeholder="手机可访问的家庭服务地址">
      <details style="margin-top:14px"><summary class="muted" style="cursor:pointer">网络接入设置</summary>
        <label>控制面地址</label><input id="c-ctrl">
        <label>网络授权 key</label><input id="c-network" type="password" autocomplete="off" placeholder="服务器已配置时无需填写">
      </details>
      <div><button class="primary" id="c-generate">生成连接码</button></div>
      <p class="muted">二维码与本页复制的连接码是同一串，扫码与粘贴等价。业务连接码5分钟有效，仅授权一个安装；网络 key 独立有效，不随二维码过期失效。</p>
    </div>
    <div class="card" id="c-result" hidden>
      <div class="row">
        <div>
          <div class="qr" id="c-qr"></div>
          <p class="muted" id="c-qrnote"></p>
        </div>
        <div style="flex:1;min-width:260px">
          <p><span class="pill" id="c-statepill">等待手机扫码</span></p>
          <p class="muted" id="c-expiry"></p>
          <textarea id="c-code" readonly aria-label="连接码"></textarea>
          <div>
            <button class="ghost" id="c-copy">复制连接码</button>
            <button class="quiet" id="c-regen">重新生成</button>
            <button class="danger" id="c-cancel">取消本次邀请</button>
          </div>
          <p class="muted" id="c-done" hidden></p>
        </div>
      </div>
      <p class="muted">生成 → 手机扫码确认 → 完成。码只在本次邀请可见时轮询状态，切后台或锁定即停止。</p>
    </div>
  </section>

  <section class="view" id="view-settings" hidden>
    <div class="card">
      <h2>服务设置</h2>
      <p>当前服务地址：<span class="mono" id="s-server"></span></p>
      <p>控制面地址：<span class="mono" id="s-ctrl"></span></p>
      <p>网络 key：<span id="s-key"></span></p>
      <p class="muted">不在此页编辑环境变量或自动重启服务。修改请改服务器配置并重启容器。</p>
      <div><button class="danger" id="s-lock">锁定并清除会话</button></div>
    </div>
  </section>
</div>
<div id="status" role="status"></div>
</div>
<script>
(function(){
"use strict";
var $=function(id){return document.getElementById(id)};
var token="",networkKey="",pollTimer=null,pollDeadline=0,currentInvite=null,sessionGen=0;
function fmt(n){if(n>=1048576)return (n/1048576).toFixed(1)+" MiB";if(n>=1024)return (n/1024).toFixed(1)+" KiB";return n+" B"}
function when(iso){if(!iso)return "暂无";try{return new Date(iso).toLocaleString()}catch(e){return iso}}
function toast(msg){var s=$("status");s.textContent=msg;s.classList.add("show");clearTimeout(s._t);s._t=setTimeout(function(){s.classList.remove("show")},2600)}
function clearSession(){token="";networkKey="";stopPoll();var a=$("admin");if(a)a.value="";var n=$("c-network");if(n)n.value="";$("app").hidden=true;$("lock").hidden=false;$("c-result").hidden=true;$("c-code").value="";currentInvite=null;sessionGen++}
async function api(path,body){
  var gen=sessionGen;
  var options={headers:{"X-Admin-Token":token},cache:"no-store",credentials:"omit"};
  if(body!==undefined){options.method="POST";options.headers["Content-Type"]="application/json";options.body=JSON.stringify(body)}
  var response=await fetch(path,options);
  var data;try{data=await response.json()}catch(e){throw new Error("服务返回异常，请检查服务版本")}
  if(gen!==sessionGen)throw new Error("会话已锁定");
  if(!response.ok){if(response.status===403&&token){clearSession();$("lock").hidden=false}throw new Error(data.error||data.detail||"操作失败")}
  return data;
}
function view(name){
  ["overview","devices","connect","settings"].forEach(function(v){$("view-"+v).hidden=(v!==name)});
  document.querySelectorAll("nav a[data-view]").forEach(function(a){a.classList.toggle("on",a.getAttribute("data-view")===name)});
  if(name==="overview")loadOverview();
  if(name==="devices")loadDevices();
}
document.querySelectorAll("nav a[data-view]").forEach(function(a){a.onclick=function(e){e.preventDefault();view(a.getAttribute("data-view"))}});
$("locklink").onclick=function(e){e.preventDefault();clearSession();$("lock").hidden=false};

$("login").onclick=async function(){var t=$("admin").value;if(!t){toast("请输入管理令牌");return}
  try{token=t;var d=await api("/v1/admin/backup-settings");$("c-server").value=d.serverUrl||"";$("c-ctrl").value=d.ctrlUrl||"";$("c-network").placeholder=d.networkKeyConfigured?"服务器已配置，无需填写":"填写 tskey 网络授权";$("s-server").textContent=d.serverUrl||"未配置";$("s-ctrl").textContent=d.ctrlUrl||"未配置";$("s-key").textContent=d.networkKeyConfigured?"已配置":"未配置";$("admin").value="";$("app").hidden=false;$("lock").hidden=true;view("overview")}
  catch(e){token="";toast(e.message)}};
$("admin").addEventListener("keydown",function(e){if(e.key==="Enter")$("login").click()});

async function loadOverview(){
  try{var d=await api("/v1/admin/backup-overview");
    $("stats").innerHTML=""
      +"<div class='stat'><div class='k'>有效设备</div><div class='v'>"+d.activeDevices+"</div></div>"
      +"<div class='stat'><div class='k'>未删除备份</div><div class='v'>"+d.liveBackups+"</div></div>"
      +"<div class='stat'><div class='k'>逻辑总大小</div><div class='v'>"+fmt(d.liveBytes)+"</div></div>"
      +"<div class='stat'><div class='k'>最近接收</div><div class='v' style='font-size:17px'>"+when(d.lastBackupAt)+"</div></div>";
    var tb=$("ov-recent").querySelector("tbody");tb.innerHTML=d.recentBackups.map(function(r){
      return "<tr><td>"+esc(r.deviceName)+"</td><td>"+when(r.uploadedAt)+"</td><td>"+r.billCount+"</td><td>"+fmt(r.sizeBytes)+"</td></tr>"}).join("")||"<tr><td colspan='4' class='muted'>暂无备份</td></tr>";
    $("ov-time").textContent="页面更新于 "+when(d.serverTime);
  }catch(e){toast(e.message)}}
$("ov-refresh").onclick=loadOverview;

async function loadDevices(){
  try{var d=await api("/v1/admin/backup-sources");var q=($("dev-q").value||"").trim().toLowerCase();
    var items=d.items.filter(function(i){return !q||i.name.toLowerCase().indexOf(q)>=0||i.deviceId.toLowerCase().indexOf(q)>=0});
    var tb=$("dev-table").querySelector("tbody");
    tb.innerHTML=items.map(function(i){
      var st=i.revoked?"<span class='pill bad'>已撤销</span>":(i.archived?"<span class='pill warn'>已归档</span>":"<span class='pill ok'>正常</span>");
      return "<tr><td>"+esc(i.name)+"<div class='mono'>"+i.deviceId.slice(0,8)+"</div></td><td>"+i.backupCount+" 份<div class='mono'>"+(i.lastBackupAt?when(i.lastBackupAt):"暂无")+"</div></td><td>"+when(i.lastSeenAt)+"</td><td>"+st+"</td><td><button class='quiet' data-arch='"+i.deviceId+"' data-on='"+(i.archived?"1":"0")+"' data-name='"+esc(i.name)+"'>"+(i.archived?"取消归档":"归档")+"</button></td></tr>";
    }).join("")||"<tr><td colspan='5' class='muted'>没有匹配的设备</td></tr>";
    tb.querySelectorAll("button[data-arch]").forEach(function(b){b.onclick=function(){
      var on=b.getAttribute("data-on")==="1",nm=b.getAttribute("data-name"),id=b.getAttribute("data-arch");
      if(!confirm("确认"+(on?"取消归档":"归档")+"“"+nm+"”？备份保留，凭证不撤销。"))return;
      api("/v1/admin/backup-sources/"+id+"/archive",{archived:!on}).then(function(){toast("已更新");loadDevices()}).catch(function(e){toast(e.message)});
    }});
  }catch(e){toast(e.message)}}
$("dev-refresh").onclick=loadDevices;
$("dev-q").addEventListener("input",loadDevices);

function stopPoll(){if(pollTimer){clearInterval(pollTimer);pollTimer=null}}
function clearCode(){stopPoll();currentInvite=null;$("c-result").hidden=true;$("c-code").value="";$("c-qr").innerHTML=""}
$("c-generate").onclick=async function(){
  $("c-generate").disabled=true;clearCode();
  try{
    networkKey=$("c-network").value||networkKey;
    var d=await api("/v1/admin/connect-code",{serverUrl:$("c-server").value,ctrlUrl:$("c-ctrl").value,authKey:networkKey,deviceName:$("c-name").value});
    $("c-network").value="";
    $("c-code").value=d.connectCode;
    if(d.qrSvg){$("c-qr").innerHTML=d.qrSvg;$("c-qrnote").textContent="扫码或复制连接码，二者等价"}
    else{$("c-qr").innerHTML="<p class='muted'>连接码过长，无法生成可扫二维码，请复制粘贴</p>";$("c-qrnote").textContent=""}
    $("c-expiry").textContent="有效至 "+new Date(d.expiresAt).toLocaleTimeString()+"（服务器时间 "+(d.serverTime?new Date(d.serverTime).toLocaleTimeString():"")+"）";
    $("c-statepill").className="pill";$("c-statepill").textContent="等待手机扫码";
    $("c-done").hidden=true;$("c-result").hidden=false;
    currentInvite=d.invitationId;startPoll();
  }catch(e){toast(e.message)}finally{$("c-generate").disabled=false}};
$("c-copy").onclick=async function(){try{await navigator.clipboard.writeText($("c-code").value);toast("连接码已复制")}catch(e){$("c-code").select();toast("请复制已选中的连接码")}};
$("c-regen").onclick=async function(){if(currentInvite){try{await api("/v1/admin/connect-codes/"+currentInvite+"/cancel",{})}catch(e){}}clearCode();$("c-generate").click()};
$("c-cancel").onclick=async function(){
  if(!currentInvite){clearCode();return}
  try{var d=await api("/v1/admin/connect-codes/"+currentInvite+"/cancel",{});stopPoll();
    $("c-statepill").className="pill"+(d.status==="completed"?" ok":" bad");
    $("c-statepill").textContent=d.status==="completed"?"已完成登记，无法取消":(d.status==="cancelled"?"已取消":"已过期");
    if(d.status==="completed")toast("该邀请已完成设备登记，未撤销设备");else{toast("已取消本次邀请");$("c-code").value="";$("c-qr").innerHTML=""}
  }catch(e){toast(e.message)}};

function startPoll(){stopPoll();pollDeadline=Date.now()+5*60*1000;
  pollTimer=setInterval(function(){
    if(document.hidden||Date.now()>pollDeadline){stopPoll();return}
    if(!currentInvite)return;
    api("/v1/admin/connect-codes/"+currentInvite).then(function(d){
      var m={pending:["pill","等待手机扫码"],authorized:["pill warn","手机已领取，等待完成登记"],completed:["pill ok","已完成设备登记"],expired:["pill bad","已过期，请重新生成"],cancelled:["pill bad","已取消"]}[d.status]||["pill",d.status];
      $("c-statepill").className=m[0];$("c-statepill").textContent=m[1];
      if(d.status==="completed"){stopPoll();$("c-code").value="";$("c-qr").innerHTML="";$("c-done").hidden=false;$("c-done").textContent="设备 "+String(d.deviceId||"").slice(0,8)+" 已完成登记。";toast("连接完成")}
      else if(d.status==="expired"||d.status==="cancelled")stopPoll();
    }).catch(function(e){stopPoll();toast(e.message)});
  },3000)}
document.addEventListener("visibilitychange",function(){if(document.hidden)stopPoll()});
window.addEventListener("pagehide",clearSession);
$("s-lock").onclick=function(){clearSession();$("lock").hidden=false;toast("已锁定")};

function esc(s){return String(s==null?"":s).replace(/[&<>"']/g,function(c){return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]})}
})();
</script></body></html>"""


@router.get("/admin/cloud-backup", response_class=HTMLResponse)
def admin_page() -> HTMLResponse:
    return HTMLResponse(PAGE, headers={
        "Cache-Control": "no-store", "Referrer-Policy": "no-referrer",
        "X-Content-Type-Options": "nosniff", "X-Frame-Options": "DENY",
        "Content-Security-Policy": "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
    })
