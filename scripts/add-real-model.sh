#!/usr/bin/env bash
# 一条命令接入真实大模型：建 provider -> 建 model -> 连通性测试 -> 建 Agent（供聊天页选择）
#
# 用法：
#   bash scripts/add-real-model.sh <供应商名> <baseUrl> <模型ID> <APIKey> [providerType] [agentId]
#
# 例：
#   bash scripts/add-real-model.sh 公司网关 https://llm.corp.local/v1 gpt-4o-mini sk-xxx
#   bash scripts/add-real-model.sh 百炼 https://dashscope.aliyuncs.com/api/v1 qwen-plus sk-xxx DASHSCOPE
#   bash scripts/add-real-model.sh 本地ollama http://127.0.0.1:11434/v1 qwen2.5 OLLAMA my-agent
#
# providerType：OPENAI_COMPATIBLE(默认) / OPENAI / DASHSCOPE / ANTHROPIC / GEMINI / OLLAMA
# 依赖：curl + bash（不需要 jq）。所有请求体经 stdin 传输，避免 Windows 下中文被转成 GBK。
set -uo pipefail

API="${API:-http://localhost:8080}"
NAME="${1:-}"
BASE_URL="${2:-}"
MODEL_ID="${3:-}"
API_KEY="${4:-}"
PROVIDER_TYPE="${5:-OPENAI_COMPATIBLE}"
AGENT_ID="${6:-${MODEL_ID}-agent}"

if [ -z "$NAME" ] || [ -z "$BASE_URL" ] || [ -z "$MODEL_ID" ] || [ -z "$API_KEY" ]; then
  echo "用法: bash scripts/add-real-model.sh <供应商名> <baseUrl> <模型ID> <APIKey> [providerType] [agentId]" >&2
  exit 64
fi

# JSON 字符串转义（反斜杠与双引号）
esc() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
id_of() { sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1; }

TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "登录失败，后端起了吗？($API)" >&2; exit 1; }
AUTH="Authorization: Bearer $TOKEN"
CT="Content-Type: application/json"

echo "== 1/4 建供应商：$NAME ($PROVIDER_TYPE) =="
PROV=$(printf '{"name":"%s","providerType":"%s","baseUrl":"%s","apiKey":"%s","status":"ENABLED"}' \
    "$(esc "$NAME")" "$(esc "$PROVIDER_TYPE")" "$(esc "$BASE_URL")" "$(esc "$API_KEY")" \
  | curl -s -X POST "$API/api/model-providers" -H "$AUTH" -H "$CT" --data-binary @-)
echo "   $(echo "$PROV" | head -c 200)"
PID=$(echo "$PROV" | id_of)
if [ -z "$PID" ]; then
  echo "   供应商已存在？尝试复用同名供应商" >&2
  PID=$(curl -s "$API/api/model-providers?current=1&size=100" -H "$AUTH" \
    | tr '}' '\n' | grep -F "\"name\":\"$NAME\"" | id_of)
fi
[ -n "$PID" ] || { echo "无法取得供应商 id，终止" >&2; exit 1; }

echo "== 2/4 建模型：$MODEL_ID =="
MODEL=$(printf '{"providerId":%s,"modelId":"%s","displayName":"%s","status":"ENABLED"}' \
    "$PID" "$(esc "$MODEL_ID")" "$(esc "$MODEL_ID")" \
  | curl -s -X POST "$API/api/models" -H "$AUTH" -H "$CT" --data-binary @-)
echo "   $(echo "$MODEL" | head -c 200)"
MID=$(echo "$MODEL" | id_of)
if [ -z "$MID" ]; then
  echo "   模型已存在？尝试复用" >&2
  MID=$(curl -s "$API/api/models?current=1&size=200&providerId=$PID" -H "$AUTH" \
    | tr '}' '\n' | grep -F "\"modelId\":\"$MODEL_ID\"" | id_of)
fi
[ -n "$MID" ] || { echo "无法取得模型 id，终止" >&2; exit 1; }

echo "== 3/4 连通性测试 =="
TEST=$(curl -s -X POST "$API/api/model-providers/$PID/test?modelId=$MID" -H "$AUTH")
echo "   $(echo "$TEST" | head -c 300)"

echo "== 4/4 建 Agent：$AGENT_ID =="
AG=$(printf '{"name":"%s","agentId":"%s","sysPrompt":"你是一个乐于助人的助手。","modelId":%s,"temperature":0.7,"permissionMode":"ALWAYS_ALLOW","maxIterations":10,"status":"ENABLED","skillIds":[],"mcpServerIds":[]}' \
    "$(esc "$AGENT_ID")" "$(esc "$AGENT_ID")" "$MID" \
  | curl -s -X POST "$API/api/agents" -H "$AUTH" -H "$CT" --data-binary @-)
echo "   $(echo "$AG" | head -c 200)"

echo
if echo "$TEST" | grep -q '"ok":true'; then
  echo "✅ 完成：打开 http://localhost:5173/chat （admin / Admin@123）→ 点「新建会话」→ 选择 Agent「$AGENT_ID」→ 开始对话"
else
  echo "⚠️  Agent 已建好，但连通性测试未通过（见上方 error）。修好 baseUrl / key 后重跑本脚本即可。"
fi
