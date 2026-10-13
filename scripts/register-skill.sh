#!/usr/bin/env bash
# 把一个标准 Skill（SKILL.md，带 YAML frontmatter）注册/更新到平台。
#
# 用法：
#   MYAGENT_ADMIN_USER=admin MYAGENT_ADMIN_PASSWORD=... scripts/register-skill.sh skills/agui-interaction/SKILL.md
#
# 为什么要有它：平台把 Skill 存在 `skill` 表（name/description/content），再同步进官方
# AgentScope 技能仓库；手工在管理台粘贴长正文容易出错、也不好版本管理。这个脚本让仓库里的
# SKILL.md 成为**唯一事实来源**：已存在就按 name 覆盖更新，不存在就新建。
#
# 两个必须注意的坑（都实测踩过）：
#   1. Windows 的 curl 读不到 Git Bash 的 /tmp 路径 → 载荷写到仓库内相对路径。
#   2. Windows 终端默认 GBK，中文/emoji 直接 print 会崩 → Python 一律按 UTF-8 读写文件与 stdin。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PAYLOAD_PATH="$REPO_ROOT/.skill-payload.json"
export PAYLOAD_PATH

SKILL_FILE="${1:-}"
if [ -z "$SKILL_FILE" ] || [ ! -f "$SKILL_FILE" ]; then
  echo "用法: scripts/register-skill.sh <SKILL.md 路径>" >&2
  exit 1
fi

BASE_URL="${MYAGENT_BASE_URL:-http://localhost:8080}"
: "${MYAGENT_ADMIN_USER:?需要设置 MYAGENT_ADMIN_USER}"
: "${MYAGENT_ADMIN_PASSWORD:?需要设置 MYAGENT_ADMIN_PASSWORD}"

# 用 Python 统一做「取 token / 解析 frontmatter / 解析响应」，避免 shell 里的编码与转义问题
python - "$BASE_URL" "$SKILL_FILE" <<'PY'
import json, os, pathlib, sys, urllib.error, urllib.request

base_url, skill_file = sys.argv[1], sys.argv[2]
user = os.environ["MYAGENT_ADMIN_USER"]
password = os.environ["MYAGENT_ADMIN_PASSWORD"]


def call(method, path, payload=None, token=None):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(base_url + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        body = err.read().decode("utf-8", "replace")
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            raise SystemExit("HTTP %s 且响应不是 JSON: %s" % (err.code, body[:200]))


token = call("POST", "/api/auth/login", {"username": user, "password": password})["data"]["token"]

text = pathlib.Path(skill_file).read_text(encoding="utf-8")
if not text.startswith("---"):
    raise SystemExit("SKILL.md 必须以 --- 开头的 YAML frontmatter 开始")
_, front, body = text.split("---", 2)
meta = {}
for line in front.strip().splitlines():
    if ":" in line:
        key, value = line.split(":", 1)
        meta[key.strip()] = value.strip()
name = meta.get("name")
if not name:
    raise SystemExit("frontmatter 缺少 name")

payload = {
    "name": name,
    "description": meta.get("description", ""),
    "content": body.strip(),
    "status": "ENABLED",
}

# 先找同名（有则更新，无则新建）
existing = call("GET", "/api/skills?current=1&size=500", token=token)
existing_id = next((r["id"] for r in existing["data"]["records"] if r["name"] == name), None)

if existing_id is None:
    result = call("POST", "/api/skills", payload, token)
    action = "已新建"
else:
    result = call("PUT", "/api/skills/%d" % existing_id, payload, token)
    action = "已更新"

if result.get("code") != 0:
    raise SystemExit("注册失败: %s" % json.dumps(result, ensure_ascii=False)[:300])

saved = result["data"]
sys.stdout.reconfigure(encoding="utf-8")
print("%s Skill: %s (id=%s, 正文 %d 字)" % (action, saved["name"], saved["id"], len(saved.get("content") or "")))
PY
