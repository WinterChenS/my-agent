#!/usr/bin/env bash
# Task 7.2 验收脚本：确认卡 / 映射卡 / 工具调用卡（可复跑）。
#
# 机制：demo-agent 的内置工具通过 AgentScope 的 CustomEvent（经官方
# CustomAgentEventConverter）发出 AG-UI 的 CUSTOM 事件；前端按第 6 节冻结契约渲染。
# 断言：
#   1) demo_confirm_card 触发 CUSTOM name=confirm_card，data 符合 ConfirmCardData
#   2) demo_mapping_card 触发 CUSTOM name=mapping_card，data 符合 MappingCardData
#   3) 工具调用卡所需事件（TOOL_CALL_START/ARGS/END/RESULT）齐全
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
cleanup() { for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done; sleep 1; }
trap cleanup EXIT
free_port() {
  local pids
  pids=$(netstat -ano 2>/dev/null | grep LISTENING | grep ":$1 " | awk '{print $5}' | sort -u)
  for pid in $pids; do [ -n "$pid" ] && taskkill //PID "$pid" //F >/dev/null 2>&1 || true; done
}
wait_for() { for _ in $(seq 1 "${2:-60}"); do [ -n "$(curl -s -m 2 "$1" || true)" ] && return 0; sleep 2; done; echo "等待超时: $1" >&2; return 1; }
id_of() { sed -n 's/.*"id":\([0-9]*\).*/\1/p'; }

echo "== 0/5 清理端口 =="
free_port 8080; free_port "$STUB_PORT"; sleep 2

TOOL_NAME="${1:-demo_confirm_card}"
TOOL_ARGS="${2:-{\"question\":\"要删除这 3 条记录吗？\"}}"
CARD_NAME="${3:-confirm_card}"

echo "== 1/5 启动桩（请求调用 $TOOL_NAME）=="
STUB_TOOL_NAME="$TOOL_NAME" STUB_TOOL_ARGS="$TOOL_ARGS" \
  python "$ROOT/tools/llm-stub/stub.py" >/tmp/p72-stub.log 2>&1 &
PIDS+=($!)
wait_for "http://localhost:$STUB_PORT/health" 15

echo "== 2/5 构建并启动后端 =="
(cd "$ROOT/backend/agent-server" && "$MVN" -B -q package -DskipTests) || { echo "构建失败" >&2; exit 1; }
nohup "$JAVA_BIN" -jar "$ROOT/backend/agent-server/target/agent-server-0.0.1-SNAPSHOT.jar" >/tmp/p72-app.log 2>&1 &
PIDS+=($!)
wait_for "$API/api/health" 60

TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
AUTH="Authorization: Bearer $TOKEN"
CT="Content-Type: application/json"
post() { curl -s -X POST "$API$1" -H "$AUTH" -H "$CT" --data-binary @-; }

echo "== 3/5 准备 demo-agent =="
PID=$(printf '{"name":"P72-Provider-%s","providerType":"OPENAI_COMPATIBLE","baseUrl":"http://localhost:%s/v1","apiKey":"sk","status":"ENABLED"}' "$RUN_ID" "$STUB_PORT" | post /api/model-providers | id_of)
MID=$(printf '{"providerId":%s,"modelId":"stub-%s"}' "$PID" "$RUN_ID" | post /api/models | id_of)
AGENT="demo-agent-$RUN_ID"
printf '{"name":"%s","agentId":"%s","sysPrompt":"你是演示助手","modelId":%s,"permissionMode":"ALWAYS_ALLOW","maxIterations":5,"status":"ENABLED","skillIds":[],"mcpServerIds":[]}' \
  "$AGENT" "$AGENT" "$MID" | post /api/agents >/dev/null
echo "   agent=$AGENT"

echo "== 4/5 跑一轮对话 =="
printf '{"threadId":"p72-%s","runId":"p72-%s-1","messages":[{"id":"m1","role":"user","content":"请演示卡片"}],"forwardedProps":{"agentId":"%s"}}' \
  "$RUN_ID" "$RUN_ID" "$AGENT" \
  | curl -sN -X POST "$API/agui/run" -H "$AUTH" -H "$CT" --data-binary @- --max-time 120 > /tmp/p72-run.txt
grep -o '"type":"[A-Z_]*"' /tmp/p72-run.txt | sed 's/"type":"//;s/"//' | tr '\n' ' '; echo
CUSTOM=$(grep -o "\"name\":\"$CARD_NAME\".\\{0,500\\}" /tmp/p72-run.txt | head -1)
echo "   CUSTOM: $CUSTOM"

echo "== 5/5 断言 =="
FAIL=0
check() { if [ "$2" = "1" ]; then echo "   PASS  $1"; else echo "   FAIL  $1"; FAIL=1; fi; }
check "出现 CUSTOM name=$CARD_NAME 事件" "$(echo "$CUSTOM" | grep -q "$CARD_NAME" && echo 1 || echo 0)"
check "工具调用卡事件齐全（TOOL_CALL_START/ARGS/END/RESULT）" \
  "$(grep -q TOOL_CALL_START /tmp/p72-run.txt && grep -q TOOL_CALL_ARGS /tmp/p72-run.txt && grep -q TOOL_CALL_END /tmp/p72-run.txt && grep -q TOOL_CALL_RESULT /tmp/p72-run.txt && echo 1 || echo 0)"

if [ "$CARD_NAME" = "confirm_card" ]; then
  check "data 符合 ConfirmCardData（title/message/options/requestId）" \
    "$(echo "$CUSTOM" | grep -q '"title"' && echo "$CUSTOM" | grep -q '"message"' && echo "$CUSTOM" | grep -q '"options"' && echo "$CUSTOM" | grep -q '"requestId"' && echo 1 || echo 0)"
else
  check "data 符合 MappingCardData（title/columns/rows）" \
    "$(echo "$CUSTOM" | grep -q '"title"' && echo "$CUSTOM" | grep -q '"columns"' && echo "$CUSTOM" | grep -q '"rows"' && echo 1 || echo 0)"
fi

echo
[ "$FAIL" = "0" ] && echo "P7.2（$CARD_NAME）验收：全部通过" || echo "P7.2（$CARD_NAME）验收：存在失败项"
exit $FAIL
