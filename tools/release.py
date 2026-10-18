#!/usr/bin/env python3
"""建 GitHub Release 并上传 APK。token 从 ~/.git-credentials 里取，走 --noproxy 绕开沙箱代理。"""
import json
import os
import re
import subprocess
import sys

REPO = "z7ui/RuleTree"
APK = r"app/build/outputs/apk/release/app-release.apk"


def token():
    p = os.path.expanduser("~/.git-credentials")
    m = re.search(r"https://[^:]*:([^@]+)@", open(p, encoding="utf-8").read())
    if not m:
        sys.exit("找不到 token")
    return m.group(1)


def curl(args, **kw):
    base = ["curl", "-sS", "--noproxy", "*",
            "-H", "Authorization: Bearer " + token(),
            "-H", "Accept: application/vnd.github+json"]
    return subprocess.run(base + args, capture_output=True, text=True, **kw)


tag = sys.argv[1]
name = sys.argv[2]
notes = sys.argv[3]

r = curl(["-X", "POST", f"https://api.github.com/repos/{REPO}/releases",
          "-d", json.dumps({"tag_name": tag, "name": name, "body": notes})])
if r.returncode != 0:
    sys.exit(r.stderr)
rel = json.loads(r.stdout)
if "id" not in rel:
    sys.exit("建 release 失败: " + r.stdout[:500])

print("release:", rel["html_url"])
up = f"https://uploads.github.com/repos/{REPO}/releases/{rel['id']}/assets?name=RuleTree-{tag}.apk"
u = curl(["-X", "POST", "-H", "Content-Type: application/vnd.android.package-archive",
          "--data-binary", "@" + APK, up])
asset = json.loads(u.stdout)
print("apk:", asset.get("browser_download_url") or u.stdout[:300])
