#!/usr/bin/env bash
# Task 10.1 门禁：MinIO 不可用时的降级行为。
#
# 期望：
#   1. 后端起得来（bucket 检查失败只告警，不阻塞启动）
#   2. 二进制上传（要走 MinIO）返回**明确的业务错误**，不是 500 堆栈
#   3. 文本上传（走 DB）仍然成功
#   4. 对话主链路不受影响
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="/c/Program Files/Java/jdk-21.0.3+9/bin/java"
API="http://localhost:8080"
STUB_PORT=8099
export PATH="/d/Program Files/nodejs:$PATH"
cd "$ROOT"
WORK=".p10minio-$RANDOM"
mkdir -p "$WORK"
PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
  rm -rf "$WORK"
  sleep 1
}
trap cleanup EXIT

free_port() {
  local pids
  pids=$(netstat -ano 2>/dev/null | grep LISTENING | grep ":$1 " | awk '{print $5}' | sort -u)
  for pid in $pids; do [ -n "$pid" ] && taskkill //PID "$pid" //F >/dev/null 2>&1 || true; done
}
wait_for() { for _ in $(seq 1 "${2:-60}"); do [ -n "$(curl -s -m 2 "$1" || true)" ] && return 0; sleep 2; done; return 1; }
id_of() { sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1; }

echo "== 0/4 清理端口 =="
free_port 8080; free_port "$STUB_PORT"; sleep 2

echo "== 1/4 用「指向不存在端口」的 MinIO 启动后端 =="
STUB_REPLY_FROM_MODEL=1 PORT=$STUB_PORT nohup python "$ROOT/tools/llm-stub/stub.py" >/tmp/p10minio-stub.log 2>&1 &
PIDS+=($!)
wait_for "http://localhost:$STUB_PORT/health" 15
MINIO_ENDPOINT="http://127.0.0.1:1" MINIO_PUBLIC_ENDPOINT="http://127.0.0.1:1" \
  nohup "$JAVA_BIN" -jar "$ROOT/backend/agent-server/target/agent-server-0.0.1-SNAPSHOT.jar" >/tmp/p10minio-app.log 2>&1 &
PIDS+=($!)
if wait_for "$API/api/health" 90; then echo "   后端仍可用（health OK）"; else echo "   后端没起来！" >&2; tail -20 /tmp/p10minio-app.log >&2; exit 1; fi
grep -o "MinIO bucket 检查/创建失败[^\"]*" /tmp/p10minio-app.log | head -1 | sed 's/^/   启动告警: /'

TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
AUTH="Authorization: Bearer $TOKEN"
CT="Content-Type: application/json"

echo "== 2/4 准备 fixture =="
RUN_ID=$(date +%s)
PID=$(printf '{"name":"P10minio-Provider-%s","providerType":"OPENAI_COMPATIBLE","baseUrl":"http://localhost:%s/v1","apiKey":"k","status":"ENABLED"}' "$RUN_ID" "$STUB_PORT" | curl -s -X POST "$API/api/model-providers" -H "$AUTH" -H "$CT" --data-binary @- | id_of)
MID=$(printf '{"providerId":%s,"modelId":"p10minio-%s"}' "$PID" "$RUN_ID" | curl -s -X POST "$API/api/models" -H "$AUTH" -H "$CT" --data-binary @- | id_of)
AG="p10minio-agent-$RUN_ID"
printf '{"name":"%s","agentId":"%s","sysPrompt":"You are a helper.","modelId":%s,"permissionMode":"ALWAYS_ALLOW","maxIterations":3,"status":"ENABLED","skillIds":[],"mcpServerIds":[]}' "$AG" "$AG" "$MID" | curl -s -X POST "$API/api/agents" -H "$AUTH" -H "$CT" --data-binary @- >/dev/null
AID=$(curl -s "$API/api/agents?current=1&size=300" -H "$AUTH" | AG="$AG" python -c "
import os,sys,json
for a in json.load(sys.stdin)['data']['records']:
    if a['agentId']==os.environ['AG']: print(a['id']); break")
SID=$(printf '{"agentId":%s,"title":"p10 minio down"}' "$AID" | curl -s -X POST "$API/api/sessions" -H "$AUTH" -H "$CT" --data-binary @- | id_of)
[ -n "$MID" ] && [ -n "$SID" ] || { echo "fixture 失败" >&2; exit 1; }

echo "== 3/4 上传：二进制应明确报错、文本应成功 =="
printf '\x89PNG\r\n\x1a\nFAKE' > "$WORK/pic.png"
printf 'text ok\n' > "$WORK/note.md"
echo -n "   二进制上传: "
curl -s -m 60 -w " [HTTP=%{http_code}]\n" -X POST "$API/api/artifacts/upload" -H "$AUTH" \
  -F "sessionId=$SID" -F "file=@$WORK/pic.png;type=image/png" | head -c 400
echo -n "   文本上传:   "
curl -s -m 60 -w " [HTTP=%{http_code}]\n" -X POST "$API/api/artifacts/upload" -H "$AUTH" \
  -F "sessionId=$SID" -F "file=@$WORK/note.md;type=text/markdown" | head -c 240

echo "== 4/4 对话主链路 =="
THREAD=$(curl -s "$API/api/sessions" -H "$AUTH" | S="$SID" python -c "
import os,sys,json
for s in json.load(sys.stdin)['data']:
    if s['id']==int(os.environ['S']): print(s['threadId']); break")
printf '{"threadId":"%s","runId":"p10minio-%s","messages":[{"id":"m1","role":"user","content":"hello"}],"forwardedProps":{"agentId":"%s"}}' "$THREAD" "$RUN_ID" "$AG" \
  | curl -sN -m 90 -X POST "$API/agui/run" -H "$AUTH" -H "$CT" --data-binary @- | grep -o '"type":"TEXT_MESSAGE_CONTENT".\{0,120\}' | head -2

# 清理 fixture
curl -s -o /dev/null -X DELETE "$API/api/sessions/$SID" -H "$AUTH"
curl -s -o /dev/null -X DELETE "$API/api/agents/$AID" -H "$AUTH"
curl -s -o /dev/null -X DELETE "$API/api/models/$MID" -H "$AUTH"
curl -s -o /dev/null -X DELETE "$API/api/model-providers/$PID" -H "$AUTH"
echo
echo "P10 MinIO 降级验收完成"
