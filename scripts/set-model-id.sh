#!/usr/bin/env bash
# 直接改某条模型记录的 modelId（不会碰其它字段），可选立刻做一次连通测试。
#
# 用法：
#   bash scripts/set-model-id.sh <模型记录id> <新的模型ID> [供应商id(可选,用于测试)]
#
# 例（把你的 stub-model 改成本地网关支持的 deepseek/deepseek-flash）：
#   bash scripts/set-model-id.sh 34 deepseek/deepseek-flash
#
# 不知道记录 id 时先列一下：
#   bash scripts/set-model-id.sh --list
set -uo pipefail

API="${API:-http://localhost:8080}"
ARG1="${1:-}"

TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "登录失败，后端起了吗？($API)" >&2; exit 1; }
AUTH="Authorization: Bearer $TOKEN"
CT="Content-Type: application/json"

if [ "$ARG1" = "--list" ] || [ -z "$ARG1" ]; then
  echo "=== 当前模型记录 ==="
  curl -s "$API/api/models?current=1&size=200" -H "$AUTH" | python -c "
import sys,json
for m in json.load(sys.stdin)['data']['records']:
    print(f\"  id={m['id']}\tproviderId={m['providerId']}\tmodelId={m['modelId']!r}\tstatus={m['status']}\")
"
  [ "$ARG1" = "--list" ] && exit 0
  echo; echo "用法: bash scripts/set-model-id.sh <模型记录id> <新的模型ID>" >&2
  exit 64
fi

MODEL_DB_ID="$ARG1"
NEW_MODEL_ID="${2:?缺少新的模型ID}"
PROVIDER_ID="${3:-}"

CUR=$(curl -s "$API/api/models/$MODEL_DB_ID" -H "$AUTH" | python -c "
import sys,json
d=json.load(sys.stdin).get('data') or {}
print(json.dumps({'providerId':d.get('providerId'),'modelId':d.get('modelId'),'displayName':d.get('displayName'),'params':d.get('params'),'status':d.get('status')}))
")
[ "$CUR" != "{}" ] || { echo "模型记录 $MODEL_DB_ID 不存在" >&2; exit 1; }
echo "当前: $CUR"

# 只改 modelId（displayName 若与旧 ID 相同则一并更新，避免两列同名）
PAYLOAD=$(printf '%s' "$CUR" | python -c "
import sys,json
d=json.load(sys.stdin)
old=d.get('modelId'); new='$NEW_MODEL_ID'
d['modelId']=new
if d.get('displayName') in (None,'',old): d['displayName']=new
print(json.dumps({k:v for k,v in d.items() if k in ('providerId','modelId','displayName','params','status')}, ensure_ascii=False))
")
echo "提交: $PAYLOAD"
RESP=$(printf '%s' "$PAYLOAD" | curl -s -X PUT "$API/api/models/$MODEL_DB_ID" -H "$AUTH" -H "$CT" --data-binary @-)
echo "结果: $(printf '%s' "$RESP" | head -c 300)"

PID="${PROVIDER_ID:-$(printf '%s' "$CUR" | python -c "import sys,json;print(json.load(sys.stdin).get('providerId'))")}"
echo
echo "=== 连通测试（modelId=$NEW_MODEL_ID）==="
curl -s -X POST "$API/api/model-providers/$PID/test?modelId=$MODEL_DB_ID" -H "$AUTH"; echo
