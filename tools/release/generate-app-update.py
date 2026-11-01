#!/usr/bin/env python3
"""Generate app-update.json from the final signed release APK.

The release APK is the source of truth. This script deliberately reads the
actual APK instead of trusting Gradle variables or a hand-written manifest.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

APPLICATION_ID = "io.github.mymoney"
CHANNEL = "stable"
MAX_NOTES_BYTES = 16 * 1024


def fail(message: str) -> "NoReturn":
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def find_build_tool(name: str) -> str:
    direct = shutil.which(name)
    if direct:
        return direct
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        fail(f"{name} not found; set ANDROID_HOME or ANDROID_SDK_ROOT")
    roots = sorted(Path(sdk).glob("build-tools/*"), key=lambda path: path.name, reverse=True)
    for root in roots:
        candidate = root / name
        if candidate.is_file():
            return str(candidate)
    fail(f"{name} not found under {sdk}/build-tools")


def apk_badging(apk: Path) -> tuple[str, int, str, int]:
    output = subprocess.check_output(
        [find_build_tool("aapt2"), "dump", "badging", str(apk)],
        text=True,
        stderr=subprocess.STDOUT,
    )
    package_line = next((line for line in output.splitlines() if line.startswith("package: ")), "")
    match = re.search(r"name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", package_line)
    if not match:
        fail("could not read package/version from APK badging")
    package, code, version = match.group(1), int(match.group(2)), match.group(3)
    min_match = re.search(r"(?:minSdkVersion|sdkVersion):'([0-9]+)'", output)
    if not min_match:
        fail("could not read minSdkVersion from APK badging")
    return package, code, version, int(min_match.group(1))


def signer_digests(apk: Path) -> list[str]:
    result = subprocess.run(
        [find_build_tool("apksigner"), "verify", "--print-certs", str(apk)],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if result.returncode != 0:
        fail(f"APK signature verification failed:\n{result.stdout}")
    values = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", result.stdout)
    if not values:
        fail("could not read signer certificate SHA-256")
    return sorted({value.lower() for value in values})


def file_digest(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            digest.update(chunk)
    return size, digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--notes-file", type=Path)
    parser.add_argument("--published-at", default=None)
    args = parser.parse_args()

    apk = args.apk.resolve()
    if not apk.is_file():
        fail(f"APK not found: {apk}")
    name = apk.name
    if not name.endswith(".apk") or "debug" in name.lower() or "unsigned" in name.lower():
        fail("only a final release APK can be published as an App update")

    package, version_code, version_name, min_sdk = apk_badging(apk)
    if package != APPLICATION_ID:
        fail(f"unexpected applicationId: {package}")
    if version_code <= 0:
        fail("versionCode must be positive")
    size, sha256 = file_digest(apk)
    if size <= 0 or size > 256 * 1024 * 1024:
        fail("APK size outside 1..256MiB")

    notes = ""
    if args.notes_file:
        notes = args.notes_file.read_text(encoding="utf-8")
    if len(notes.encode("utf-8")) > MAX_NOTES_BYTES:
        fail("release notes exceed 16KiB")

    published_at = args.published_at or datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    manifest = {
        "kind": "MyMoneyAppUpdate",
        "schemaVersion": 1,
        "applicationId": APPLICATION_ID,
        "channel": CHANNEL,
        "versionName": version_name,
        "versionCode": version_code,
        "minSdk": min_sdk,
        "publishedAt": published_at,
        "notes": notes,
        "apk": {
            "assetName": name,
            "sizeBytes": size,
            "sha256": sha256,
            "signerSha256": signer_digests(apk),
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {args.output} ({name}, {version_name}/{version_code})")


if __name__ == "__main__":
    main()
