#!/usr/bin/env python3
"""在 git push 被网络挡住时，用 GitHub API 造一个同样的提交推上去。

做法：读本地 HEAD 的文件内容 -> 建 blob -> 用 base_tree 合成新树 -> 建 commit -> 移动 main 指针。
只同步 base..HEAD 之间变过的文件，其它文件沿用远端现有的树。
"""
import base64
import json
import re
import subprocess
import sys

REPO = "z7ui/RuleTree"
BASE = sys.argv[1] if len(sys.argv) > 1 else "fc8f5df"


def token():
    txt = open(r"C:/Users/z7ui/.git-credentials", encoding="utf-8").read()
    return re.search(r"https://[^:]*:([^@]+)@", txt).group(1)


TOK = token()


def api(method, url, data=None):
    cmd = ["curl", "-sS", "--noproxy", "*", "-X", method,
           "-H", "Authorization: Bearer " + TOK,
           "-H", "Accept: application/vnd.github+json",
           "-H", "Content-Type: application/json", url]
    tmp = None
    if data is not None:
        # Windows 命令行有 32K 上限，大 payload 走文件
        import tempfile
        fd, tmp = tempfile.mkstemp(suffix=".json")
        with open(fd, "w", encoding="utf-8") as f:
            json.dump(data, f)
        cmd += ["-d", "@" + tmp]
    out = subprocess.run(cmd, capture_output=True, text=True)
    if tmp:
        import os
        os.unlink(tmp)
    try:
        return json.loads(out.stdout)
    except Exception:
        print("API 返回不是 JSON:", out.stdout[:300], out.stderr[:200])
        sys.exit(1)


def sh(*args):
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout.strip()


ref = api("GET", f"https://api.github.com/repos/{REPO}/git/ref/heads/main")
head_sha = ref["object"]["sha"]
commit = api("GET", f"https://api.github.com/repos/{REPO}/git/commits/{head_sha}")
base_tree = commit["tree"]["sha"]
print("远端 HEAD", head_sha[:8], "树", base_tree[:8])

files = []
for line in sh("git", "diff", "--name-status", BASE, "HEAD").splitlines():
    parts = line.split("\t")
    if parts[0].startswith("D"):
        files.append((parts[1], None))
    else:
        files.append((parts[-1], parts[-1]))

entries = []
for path, local in files:
    if local is None:
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
        continue
    raw = open(path, "rb").read()
    blob = api("POST", f"https://api.github.com/repos/{REPO}/git/blobs",
               {"content": base64.b64encode(raw).decode(), "encoding": "base64"})
    entries.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
    print("blob", path, blob["sha"][:8])

tree = api("POST", f"https://api.github.com/repos/{REPO}/git/trees",
           {"base_tree": base_tree, "tree": entries})
msg = sh("git", "log", "-1", "--pretty=%B")
new = api("POST", f"https://api.github.com/repos/{REPO}/git/commits",
          {"message": msg, "tree": tree["sha"], "parents": [head_sha]})
print("新提交", new["sha"][:8])

api("PATCH", f"https://api.github.com/repos/{REPO}/git/refs/heads/main",
    {"sha": new["sha"], "force": False})
print("main 已更新")
