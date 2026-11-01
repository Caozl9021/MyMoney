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


def main() -> None:
    parser = argparse.ArgumentParser(prog="mymoney-home")
    sub = parser.add_subparsers(dest="command", required=True)
    pair = sub.add_parser("pair-code", help="issue a one-time pairing code")
    pair.add_argument("--base-url", default=os.getenv("MYMONEY_BASE_URL", "http://127.0.0.1:8080"))
    pair.add_argument("--admin-token", default=os.getenv("MYMONEY_ADMIN_TOKEN", ""))
    sub.add_parser("backup-audit", help="list orphan/partial/missing backup files without deleting")
    args = parser.parse_args()
    if args.command == "pair-code":
        pair_code(args.base_url, args.admin_token)
    elif args.command == "backup-audit":
        from .backups import audit_files
        print(json.dumps(audit_files(), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
