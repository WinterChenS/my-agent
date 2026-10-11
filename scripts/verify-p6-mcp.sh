#!/usr/bin/env bash
# Task 6.1 验收脚本：MCP Server 注册 + 工具同步 + 绑定后真正调用（可复跑）。
#
# 用官方 everything 测试 server（stdio）作为真实 MCP 服务端。
# 断言：
#   1) sync-tools 返回非空工具清单并写入 tool_cache
#   2) 错误地址返回业务错误（非 500）
#   3) 绑定 MCP 的 Agent 对话时出现 TOOL_CALL_* 事件，且工具由 MCP server 真实执行并回传结果
#      注：实测 AgentScope 的 McpServerRegistrar 用**裸工具名**注册（不做 server__tool 前缀），
#          因此不断言前缀，而是断言工具结果来自 MCP server 的真实回显。
#   4) 供应商下有绑定 → 删除返回 409
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="/c/Program Files/Java/jdk-21.0.3+9/bin/java"
MVN="/c/tools/apache-maven-3.9.16/bin/mvn"
API="http://localhost:8080"
STUB_PORT="${STUB_PORT:-8099}"
export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Java\\jdk-21.0.3+9}"
export PATH="/d/Program Files/nodejs:/c/tools/apache-maven-3.9.16/bin:$PATH"

RUN_ID=$(date +%s)
# Windows 下 npx 是 .cmd，Java 直接 spawn 会找不到；用 cmd /c 包一层
if [ -n "${WINDIR:-}" ]; then MCP_CMD="cmd"; MCP_ARGS='["/c","npx","-y","@modelcontextprotocol/server-everything","stdio"]';
else MCP_CMD="npx"; MCP_ARGS='["-y","@modelcontextprotocol/server-everything","stdio"]'; fi

PIDS=()
cleanup() { for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done; sleep 1; }
trap cleanup EXIT

free_port() {
  local pids
  pids=$(netstat -ano 2>/dev/null | grep LISTENING | grep ":$1 " | awk '{print $5}' | sort -u)
  for pid in $pids; do [ -n "$pid" ] && taskkill //PID "$pid" //F >/dev/null 2>&1 || true; done
}
wait_for() { for _ in $(seq 1 "${2:-60}"); do [ -n "$(curl -s -m 2 "$1" || true)" ] && return 0; sleep 2; done; echo "等待超时: $1" >&2; return 1; }

echo "== 0/7 清理端口 =="
free_port 8080; free_port "$STUB_PORT"; sleep 2

echo "== 1/7 启动模型桩（会主动调用 echo 工具）=="
STUB_TOOL_NAME="echo" STUB_TOOL_ARGS='{"message":"hello-mcp"}' \
  python "$ROOT/tools/llm-stub/stub.py" >/tmp/p6-stub.log 2>&1 &
PIDS+=($!)
wait_for "http://localhost:$STUB_PORT/health" 15

echo "== 2/7 构建并启动后端 =="
(cd "$ROOT/backend/agent-server" && "$MVN" -B -q package -DskipTests) || { echo "构建失败" >&2; exit 1; }
nohup "$JAVA_BIN" -jar "$ROOT/backend/agent-server/target/agent-server-0.0.1-SNAPSHOT.jar" >/tmp/p6-app.log 2>&1 &
PIDS+=($!)
wait_for "$API/api/health" 60

TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "登录失败" >&2; exit 1; }
AUTH="Authorization: Bearer $TOKEN"
CT="Content-Type: application/json"
post() { curl -s -X POST "$API$1" -H "$AUTH" -H "$CT" --data-binary @-; }
id_of() { sed -n 's/.*"id":\([0-9]*\).*/\1/p'; }

echo "== 3/7 注册 MCP Server（STDIO: npx everything）=="
MCP_ID=$(printf '{"name":"mcp-everything-%s","transport":"STDIO","command":"%s","args":%s,"status":"ENABLED"}' \
  "$RUN_ID" "$MCP_CMD" "$MCP_ARGS" | post /api/mcp-servers | id_of)
[ -n "$MCP_ID" ] || { echo "MCP Server 创建失败" >&2; exit 1; }
echo "   mcpServerId=$MCP_ID"

echo "== 4/7 sync-tools =="
SYNC=$(curl -s -X POST "$API/api/mcp-servers/$MCP_ID/sync-tools" -H "$AUTH")
echo "$SYNC" | head -c 400; echo
TOOL_COUNT=$(echo "$SYNC" | grep -o '"name":"' | wc -l)
echo "   同步到工具数: $TOOL_COUNT"

echo "== 5/7 错误地址 → 业务错误（非 500）=="
BAD_ID=$(printf '{"name":"mcp-bad-%s","transport":"SSE","endpointUrl":"http://127.0.0.1:1/sse","status":"ENABLED"}' "$RUN_ID" | post /api/mcp-servers | id_of)
BAD_HTTP=$(curl -s -o /tmp/p6-bad.json -w "%{http_code}" -X POST "$API/api/mcp-servers/$BAD_ID/sync-tools" -H "$AUTH")
echo "   HTTP=$BAD_HTTP body=$(head -c 200 /tmp/p6-bad.json)"

echo "== 6/7 绑定 MCP 的 Agent 对话（期望 TOOL_CALL_* 事件与真实工具结果）=="
PID=$(printf '{"name":"P6-Provider-%s","providerType":"OPENAI_COMPATIBLE","baseUrl":"http://localhost:%s/v1","apiKey":"sk-p6","status":"ENABLED"}' "$RUN_ID" "$STUB_PORT" | post /api/model-providers | id_of)
MID=$(printf '{"providerId":%s,"modelId":"stub-model-%s"}' "$PID" "$RUN_ID" | post /api/models | id_of)
AGENT="p6-agent-$RUN_ID"
printf '{"name":"%s","agentId":"%s","sysPrompt":"你是助手","modelId":%s,"permissionMode":"ALWAYS_ALLOW","maxIterations":5,"status":"ENABLED","skillIds":[],"mcpServerIds":[%s]}' \
  "$AGENT" "$AGENT" "$MID" "$MCP_ID" | post /api/agents >/dev/null

RUN_OUT=$(printf '{"threadId":"%s","runId":"%s-1","messages":[{"id":"m1","role":"user","content":"请调用 echo 工具"}],"forwardedProps":{"agentId":"%s"}}' "$AGENT" "$AGENT" "$AGENT" \
  | curl -sN -X POST "$API/agui/run" -H "$AUTH" -H "$CT" --data-binary @- --max-time 120)
echo "$RUN_OUT" | grep -o '"type":"[A-Z_]*"' | sed 's/"type":"//;s/"//' | tr '\n' ' '; echo
NAMESPACED=$(grep -o "TOOL-CALL-HIT=.*" /tmp/p6-stub.log | tail -1 | cut -d= -f2)
echo "   模型请求里看到的工具名: ${NAMESPACED:-<无>}"
TOOL_RESULT=$(echo "$RUN_OUT" | grep -o '"type":"TOOL_CALL_RESULT".\{0,300\}' | head -1)
echo "   工具结果事件: $(echo "$TOOL_RESULT" | head -c 200)"

# 契约：MCP 工具名带 Server 命名空间（server__tool）。实测模型看到的工具名应为 everything__echo。
ECHO_OK=$(echo "$TOOL_RESULT" | grep -q 'Echo: hello-mcp' && echo 1 || echo 0)
PREFIX_OK=$(echo "${NAMESPACED:-}" | grep -q '__echo$' && echo 1 || echo 0)
# 事件里的工具名也必须是带前缀的那个（否则 Agent 侧和实际注册不一致）
EVENT_PREFIX_OK=$(echo "$RUN_OUT" | grep -q '__echo' && echo 1 || echo 0)

echo "== 7/7 断言 =="
FAIL=0
check() { if [ "$2" = "1" ]; then echo "   PASS  $1"; else echo "   FAIL  $1"; FAIL=1; fi; }
check "sync-tools 返回非空工具清单" "$([ "$TOOL_COUNT" -gt 0 ] && echo 1 || echo 0)"
check "错误地址返回业务错误（4xx，非 500）" "$(echo "$BAD_HTTP" | grep -qE '^4' && echo 1 || echo 0)"
check "MCP 工具进入了模型可见的工具列表" "$(echo "$RUN_OUT" | grep -q 'TOOL_CALL_START' && echo 1 || echo 0)"
check "工具真的执行并回传结果（TOOL_CALL_RESULT）" "$(echo "$RUN_OUT" | grep -q 'TOOL_CALL_RESULT' && echo 1 || echo 0)"
check "结果来自 MCP server 的真实回显（Echo: hello-mcp）" "$ECHO_OK"
check "工具名带 Server 命名空间（模型看到 <server>__echo）" "$PREFIX_OK"
check "TOOL_CALL_* 事件里的工具名同样带命名空间" "$EVENT_PREFIX_OK"
check "删除被绑定的 MCP Server 返回 409" "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$API/api/mcp-servers/$MCP_ID" -H "$AUTH" | grep -q 409 && echo 1 || echo 0)"

echo
[ "$FAIL" = "0" ] && echo "P6 验收：全部通过" || echo "P6 验收：存在失败项"
exit $FAIL
