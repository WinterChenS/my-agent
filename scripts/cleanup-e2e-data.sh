#!/usr/bin/env bash
# 清理实施期 E2E 测试数据（默认只预览，加 --yes 才真正删除）。
#
# 背景：Phase 2-9 的验收脚本在开发库 agent_platform 里留下了大量测试用的
# provider / model / agent / 会话，会影响你在聊天页自测时的观感。
#
# 用法：
#   bash scripts/cleanup-e2e-data.sh          # 预览将删除什么
#   bash scripts/cleanup-e2e-data.sh --yes    # 真正删除
#
# 保留：admin 用户、官方 agentscope schema、以及名称不含测试前缀的数据。
set -uo pipefail

API="${API:-http://localhost:8080}"
APPLY="${1:-}"

# 测试数据命名前缀（实施期脚本生成的）
AGENT_PREFIXES="p3-agent p5- p6-agent p7-agent p7b-agent p8-agent p9- demo-agent dbg- factory- E2E- e2e- Agent- rollback- skill-test-agent stub-"
PROVIDER_PREFIXES="E2E- P3- P5- P6- P7- P8- P9- D1- DBG- FactoryTest- AgentTest- SkillTest- rollback- P72- 示例"
SKILL_PREFIXES="e2e-skill agent-test-skill p9-skill rollback-skill 测试"
MODEL_PREFIXES="p3-model stub- factory-test-model agent-test-model p3-mcp gpt-4o-mini"

TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "登录失败，后端没起？($API)" >&2; exit 1; }
AUTH="Authorization: Bearer $TOKEN"
CT="Content-Type: application/json"

matches() { # list, prefixes
  local item
  for item in $2; do
    case "$1" in *"$item"*) return 0 ;; esac
  done
  return 1
}

del() { # path, id, label
  if [ "$APPLY" = "--yes" ]; then
    code=$(curl -s -o /tmp/cu.out -w "%{http_code}" -X DELETE "$API$1/$2" -H "$AUTH")
    echo "   DELETE $1/$2 ($3) -> HTTP $code$( [ "$code" != "200" ] && echo " $(head -c 100 /tmp/cu.out)" )"
  else
    echo "   would DELETE $1/$2 ($3)"
  fi
}

echo "== agent（先删，解除对 skill/mcp 的绑定）=="
curl -s "$API/api/agents?current=1&size=500" -H "$AUTH" | python -c "
import sys,json
for a in json.load(sys.stdin)['data']['records']:
    print(a['id'], a['agentId'])
" 2>/dev/null | while read -r id aid; do
  matches "$aid" "$AGENT_PREFIXES" && del /api/agents "$id" "$aid"
done

echo "== session（会话与消息）=="
curl -s "$API/api/sessions" -H "$AUTH" | python -c "
import sys,json
for s in json.load(sys.stdin)['data']:
    print(s['id'], s.get('threadId') or '')
" 2>/dev/null | while read -r id tid; do
  # 保留 live-smoke-1（自测冒烟用），其余实施期会话都算测试数据
  case "$tid" in live-smoke-1) echo "   keep session $id ($tid)" ;; *) del /api/sessions "$id" "$tid" ;; esac
done

echo "== mcp-server =="
curl -s "$API/api/mcp-servers?current=1&size=200" -H "$AUTH" | python -c "
import sys,json
for m in json.load(sys.stdin)['data']['records']:
    print(m['id'], m['name'])
" 2>/dev/null | while read -r id name; do
  case "$name" in mcp-*|p6-*|p7*|p9-*|dbg-*|e2e-*) del /api/mcp-servers "$id" "$name" ;; esac
done

echo "== skill =="
curl -s "$API/api/skills?current=1&size=200" -H "$AUTH" | python -c "
import sys,json
for s in json.load(sys.stdin)['data']['records']:
    print(s['id'], s['name'])
" 2>/dev/null | while read -r id name; do
  matches "$name" "$SKILL_PREFIXES" && del /api/skills "$id" "$name"
done

echo "== model / provider =="
curl -s "$API/api/models?current=1&size=500" -H "$AUTH" | python -c "
import sys,json
for m in json.load(sys.stdin)['data']['records']:
    print(m['id'], m['modelId'])
" 2>/dev/null | while read -r id mid; do
  matches "$mid" "$MODEL_PREFIXES" && del /api/models "$id" "$mid"
done
curl -s "$API/api/model-providers?current=1&size=200" -H "$AUTH" | python -c "
import sys,json
for p in json.load(sys.stdin)['data']['records']:
    print(p['id'], p['name'])
" 2>/dev/null | while read -r id name; do
  matches "$name" "$PROVIDER_PREFIXES" && del /api/model-providers "$id" "$name"
done

echo
if [ "$APPLY" = "--yes" ]; then
  echo "已执行删除。若某些项因『被引用/被绑定』失败，属正常保护，可先删上层再重跑。"
  echo "注意：token_usage / approval_record 明细按设计保留（审计数据不应被业务删除删除）；如需清空请直接操作数据库。"
else
  echo "以上为预览。确认无误后执行： bash scripts/cleanup-e2e-data.sh --yes"
fi
