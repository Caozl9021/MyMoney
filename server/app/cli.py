from __future__ import annotations

import argparse
import json
import os
import urllib.request


def pair_code(base_url: str, admin_token: str) -> None:
    request = urllib.request.Request(
        base_url.rstrip("/") + "/v1/admin/pair-code",
        method="POST",
        headers={"X-Admin-Token": admin_token},
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        data = json.load(response)
    print(data["code"])


def connect_code(
    base_url: str,
    admin_token: str,
    ctrl_url: str,
    auth_key: str,
    server_url: str,
    device_name: str,
    reuse_code: str,
) -> None:
    """签发五分钟、一次有效的云备份授权，不复用旧永久业务码。"""
    if reuse_code.strip():
        raise ValueError("云备份不支持复用业务连接码，请重新生成连接码")
    request = urllib.request.Request(
        base_url.rstrip("/") + "/v1/admin/connect-code",
        method="POST",
        headers={"X-Admin-Token": admin_token, "Content-Type": "application/json"},
        data=json.dumps({"ctrlUrl": ctrl_url, "authKey": auth_key,
                         "serverUrl": server_url, "deviceName": device_name}).encode("utf-8"),
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        print(json.load(response)["connectCode"])


def main() -> None:
    parser = argparse.ArgumentParser(prog="mymoney-home")
    sub = parser.add_subparsers(dest="command", required=True)
    pair = sub.add_parser("pair-code", help="issue a one-time pairing code")
    pair.add_argument("--base-url", default=os.getenv("MYMONEY_BASE_URL", "http://127.0.0.1:8080"))
    pair.add_argument("--admin-token", default=os.getenv("MYMONEY_ADMIN_TOKEN", ""))
    connect = sub.add_parser(
        "connect-code",
        help="issue a pairing code and assemble a full MMJOIN2 connect code (tskey + server + ctrl + code)",
    )
    connect.add_argument("--base-url", default=os.getenv("MYMONEY_BASE_URL", "http://127.0.0.1:8080"))
    connect.add_argument("--admin-token", default=os.getenv("MYMONEY_ADMIN_TOKEN", ""))
    connect.add_argument("--ctrl-url", default=os.getenv("MYMONEY_CTRL_URL", "https://controlplane.tailscale.com"))
    connect.add_argument("--auth-key", default="", help="tskey-… 网络授权；为空用服务器配置")
    connect.add_argument("--server-url", default=os.getenv("MYMONEY_SERVER_URL", ""))
    connect.add_argument("--device-name", default="")
    connect.add_argument("--reuse-code", default="", help="旧参数已停用；云备份每次签发短时业务授权")
    sub.add_parser("backup-audit", help="list orphan/partial/missing backup files without deleting")
    args = parser.parse_args()
    if args.command == "pair-code":
        pair_code(args.base_url, args.admin_token)
    elif args.command == "connect-code":
        connect_code(
            args.base_url,
            args.admin_token,
            args.ctrl_url,
            args.auth_key,
            args.server_url,
            args.device_name,
            args.reuse_code,
        )
    elif args.command == "backup-audit":
        from .backups import audit_files
        print(json.dumps(audit_files(), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
