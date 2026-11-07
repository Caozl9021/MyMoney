"""组装 MMJOIN2 完整连接码。

连接码 = `MMJOIN2.` + base64url(JSON)，字段与 App 端 `FamilyInvite` v2 对齐：
  {"v":2,"ctrl":<控制面地址>,"key":<tskey 网络授权>,"server":<家庭服务地址>,
   "name":<设备名，可空>,"code":<业务配对码>}

一次粘贴即可在 App 完成「设地址 + 网络授权 + 业务配对」，不再分别填地址和配对码。
把裸 tskey 当连接码是错的；也绝不把 tskey/配对码写进日志或文档。
"""

from __future__ import annotations

import base64
import json


def build_connect_code(
    *,
    ctrl_url: str,
    auth_key: str,
    server_url: str,
    pair_code: str,
    device_name: str = "",
) -> str:
    """返回可直接粘贴到 App 的 MMJOIN2 完整连接码。"""
    if not auth_key.startswith("tskey-"):
        raise ValueError("网络授权码应以 tskey- 开头")
    if not pair_code.strip():
        raise ValueError("缺少业务配对码")
    if not server_url.strip():
        raise ValueError("缺少家庭服务地址")
    payload = {
        "v": 2,
        "ctrl": ctrl_url.strip(),
        "key": auth_key.strip(),
        "server": server_url.strip().rstrip("/"),
        "name": device_name.strip(),
        "code": pair_code.strip(),
    }
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    encoded = base64.urlsafe_b64encode(body).decode("ascii").rstrip("=")
    return "MMJOIN2." + encoded
