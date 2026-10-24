#!/usr/bin/env python3
"""Writes booksource-check/out/app-version.json for the APK in releases/ — the file the app's "check for updates" reads.

Usage: python3 -I scripts/publish-release.py "release notes" [--repo owner/repo] [--branch main]

Build the release APK first and copy it to releases/openread-<versionName>.apk. The version comes from
app/build.gradle.kts, so the manifest cannot drift from the build.
"""
import hashlib
import json
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parent.parent
gradle = (root / "app/build.gradle.kts").read_text()
code = int(re.search(r'appVersionCode"\) as String\?\)\?\.toInt\(\) \?: (\d+)', gradle).group(1))
name = re.search(r'appVersionName"\) as String\?\) \?: "([^"]+)"', gradle).group(1)

args = sys.argv[1:]
opt = {a: args[i + 1] for i, a in enumerate(args) if a in ("--repo", "--branch") and i + 1 < len(args)}
notes = next((a for i, a in enumerate(args) if not a.startswith("--") and (i == 0 or args[i - 1] not in ("--repo", "--branch"))), "")
repo = opt.get("--repo", "read0001/read")
branch = opt.get("--branch", "main")

apk = root / f"releases/openread-{name}.apk"
if not apk.exists():
    sys.exit(f"missing {apk}: build the release APK and copy it there first")
data = apk.read_bytes()
manifest = {
    "versionCode": code,
    "versionName": name,
    "notes": notes,
    "url": f"https://raw.githubusercontent.com/{repo}/{branch}/releases/openread-{name}.apk",
    "size": len(data),
    "sha256": hashlib.sha256(data).hexdigest(),
}
out = root / "booksource-check/out/app-version.json"
out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
print(f"wrote {out}: {name} ({code}), {len(data)} bytes")
