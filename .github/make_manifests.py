#!/usr/bin/env python3
"""Generate TVBox-style update manifests for each built APK in dist/.
Called by debug-build.yml after assembling APKs, before gh release create.
Outputs one <apk-basename>.json per apk into dist/.
"""
import hashlib
import json
import os
import glob

tag = os.environ.get("TAG", "")
repo = os.environ.get("GITHUB_REPOSITORY", "yydfys/webhtv-bd")
version_name = os.environ.get("VERSION_NAME", "5.6.0")
version_code = os.environ.get("VERSION_CODE", "560")

if not tag:
    raise SystemExit("TAG env required")

release_url = "https://github.com/%s/releases/download/%s" % (repo, tag)
notes = "WebHTV 自动构建 Release (%s)" % tag

apks = sorted(glob.glob("dist/*.apk"))
if not apks:
    raise SystemExit("no apk found in dist/")

for apk in apks:
    name = os.path.basename(apk)
    size = os.path.getsize(apk)
    sha256 = hashlib.sha256(open(apk, "rb").read()).hexdigest()
    base = name[:-4]
    manifest = {
        "name": tag,
        "versionName": version_name,
        "code": int(version_code),
        "channel": "stable",
        "apk": release_url + "/" + name,
        "size": size,
        "sha256": sha256,
        "downloads": {"github": {"url": release_url + "/" + name}},
        "notes": notes,
    }
    mp = "dist/" + base + ".json"
    with open(mp, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print("manifest:", mp, manifest["apk"])
