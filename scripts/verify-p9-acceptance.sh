#!/usr/bin/env bash
# Task 9.1 全链路验收剧本（可复跑）。
#
# 流程：清理端口 -> 模型桩 -> 后端 -> 建齐 fixture（provider/model/skill/MCP/两种 Agent）
#       -> Vite -> Playwright 三个剧本（工具卡 / 审批卡批准 / 审批卡拒绝）
# 产出：docs/acceptance/task-9.1-*.png
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="/c/Program Files/Java/jdk-21.0.3+9/bin/java"
MVN="/c/tools/apache-maven-3.9.16/bin/mvn"
API="http://localhost:8080"
STUB_PORT="${STUB_PORT:-8099}"
export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Java\\jdk-21.0.3+9}"
export PATH="/d/Program Files/nodejs:/c/tools/apache-maven-3.9.16/bin:$PATH"

RUN_ID=$(date +%s)
PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
  sleep 1
}
trap cleanup EXIT

free_port() {
  local pids
  pids=$(netstat -ano 2>/dev/null | grep LISTENING | grep ":$1 " | awk '{print $5}' | sort -u)
  for pid in $pids; do [ -n "$pid" ] && taskkill //PID "$pid" //F >/dev/null 2>&1 || true; done
}
wait_for() { for _ in $(seq 1 "${2:-60}"); do [ -n "$(curl -s -m 2 "$1" || true)" ] && return 0; sleep 2; done; echo "等待超时: $1" >&2; return 1; }
id_of() { sed -n 's/.*"id":\([0-9]*\).*/\1/p'; }

echo "== 0/6 清理端口 =="
free_port 5173; free_port 8080; free_port "$STUB_PORT"; sleep 2

echo "== 1/6 启动模型桩（会主动请求调用 echo）=="
STUB_TOOL_NAME="echo" STUB_TOOL_ARGS='{"message":"p9-acceptance"}' \
  STUB_CHUNK_DELAY_MS=120 \
  python "$ROOT/tools/llm-stub/stub.py" >/tmp/p9-stub.log 2>&1 &
PIDS+=($!)
wait_for "http://localhost:$STUB_PORT/health" 15

echo "== 2/6 构建并启动后端 =="
(cd "$ROOT/backend/agent-server" && "$MVN" -B -q package -DskipTests) || { echo "构建失败" >&2; exit 1; }
nohup "$JAVA_BIN" -jar "$ROOT/backend/agent-server/target/agent-server-0.0.1-SNAPSHOT.jar" >/tmp/p9-app.log 2>&1 &
PIDS+=($!)
wait_for "$API/api/health" 60

TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "登录失败" >&2; exit 1; }
AUTH="Authorization: Bearer $TOKEN"
CT="Content-Type: application/json"
post() { curl -s -X POST "$API$1" -H "$AUTH" -H "$CT" --data-binary @-; }

echo "== 3/6 建 fixture：provider / model / skill / MCP / 两个 Agent =="
PID=$(printf '{"name":"P9-Provider-%s","providerType":"OPENAI_COMPATIBLE","baseUrl":"http://localhost:%s/v1","apiKey":"sk-p9","status":"ENABLED"}' "$RUN_ID" "$STUB_PORT" | post /api/model-providers | id_of)
MID=$(printf '{"providerId":%s,"modelId":"stub-%s"}' "$PID" "$RUN_ID" | post /api/models | id_of)

# provider 连通性测试（剧本要求：test 通过）
TEST=$(curl -s -X POST "$API/api/model-providers/$PID/test" -H "$AUTH")
echo "   provider test: $(echo "$TEST" | head -c 120)"

SKILL_ID=$(printf '{"name":"p9-skill-%s","description":"验收 Skill","content":"回复末尾加【OK】","status":"ENABLED"}' "$RUN_ID" | post /api/skills | id_of)
MCP_ID=$(printf '{"name":"p9-mcp-%s","transport":"STDIO","command":"cmd","args":["/c","npx","-y","@modelcontextprotocol/server-everything","stdio"],"status":"ENABLED"}' "$RUN_ID" | post /api/mcp-servers | id_of)
SYNC=$(curl -s -X POST "$API/api/mcp-servers/$MCP_ID/sync-tools" -H "$AUTH")
echo "   MCP sync 工具数: $(echo "$SYNC" | grep -o '"name":"' | wc -l)"

ALLOW_AGENT="p9-allow-$RUN_ID"
APPROVAL_AGENT="p9-approval-$RUN_ID"
printf '{"name":"%s","agentId":"%s","sysPrompt":"你是验收助手","modelId":%s,"permissionMode":"ALWAYS_ALLOW","maxIterations":5,"status":"ENABLED","skillIds":[%s],"mcpServerIds":[%s]}' \
  "$ALLOW_AGENT" "$ALLOW_AGENT" "$MID" "$SKILL_ID" "$MCP_ID" | post /api/agents >/dev/null
printf '{"name":"%s","agentId":"%s","sysPrompt":"你是验收助手","modelId":%s,"permissionMode":"REQUIRE_APPROVAL","maxIterations":5,"status":"ENABLED","skillIds":[%s],"mcpServerIds":[%s]}' \
  "$APPROVAL_AGENT" "$APPROVAL_AGENT" "$MID" "$SKILL_ID" "$MCP_ID" | post /api/agents >/dev/null
echo "   allow=$ALLOW_AGENT approval=$APPROVAL_AGENT"

echo "== 4/6 先跑一次对话，保证审计看板有数据 =="
printf '{"threadId":"p9-seed-%s","runId":"p9-seed-%s","messages":[{"id":"m1","role":"user","content":"你好"}],"forwardedProps":{"agentId":"%s"}}' \
  "$RUN_ID" "$RUN_ID" "$ALLOW_AGENT" \
  | curl -sN -X POST "$API/agui/run" -H "$AUTH" -H "$CT" --data-binary @- --max-time 90 >/dev/null
echo "   audit: $(curl -s "$API/api/audit/token/summary" -H "$AUTH" | head -c 200)"

echo "== 5/6 启动 Vite =="
(cd "$ROOT/frontend" && nohup npm run dev >/tmp/p9-vite.log 2>&1 &)
sleep 1
wait_for "http://localhost:5173/" 40
grep -q 5173 /tmp/p9-vite.log || { echo "Vite 未监听 5173" >&2; tail -5 /tmp/p9-vite.log >&2; exit 1; }

echo "== 6/6 Playwright 验收剧本 =="
mkdir -p "$ROOT/docs/acceptance"
export E2E_ALLOW_AGENT="$ALLOW_AGENT"
export E2E_APPROVAL_AGENT="$APPROVAL_AGENT"
(cd "$ROOT/frontend" && npx playwright test e2e/acceptance.spec.ts e2e/audit.spec.ts)
RESULT=$?
echo "playwright exit=$RESULT"
ls -1 "$ROOT/docs/acceptance"/*.png 2>/dev/null
exit $RESULT
