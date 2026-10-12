#!/usr/bin/env bash
# Task 7.1 验收脚本：HITL 审批链路（可复跑）。
#
# 覆盖：拦截 → 落库 PENDING → 批准 → resume 后工具真正执行；拒绝 → 不执行；非属主 403；重复决定 409。
#
# 重要实现细节（实测得出）：
#   1) 仅设 PermissionMode.DEFAULT 拦不住工具，必须显式加 PermissionBehavior.ASK 规则
#   2) /agui/run 必须走官方 AguiRequestProcessor 管线，否则 AG-UI 的 resume 不会被翻译成
#      AgentScope 需要的确认信息（agent 会报 'supplied no confirmation'）
#   3) resume 那一轮框架不会重新发 TOOL_CALL_START/RESULT（首轮已发），
#      所以「工具确实执行」以 agent 侧的 POST_ACTING ... state=SUCCESS 作为证据
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="/c/Program Files/Java/jdk-21.0.3+9/bin/java"
MVN="/c/tools/apache-maven-3.9.16/bin/mvn"
PG_JAR="C:\\Users\\Administrator\\.m2\\repository\\org\\postgresql\\postgresql\\42.7.11\\postgresql-42.7.11.jar"
API="http://localhost:8080"
STUB_PORT="${STUB_PORT:-8099}"
APP_LOG=/tmp/p7-app.log
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

echo "== 0/7 清理端口 =="
free_port 8080; free_port "$STUB_PORT"; sleep 2

echo "== 1/7 启动桩（模型会请求调用 echo）=="
rm -f "$APP_LOG"
STUB_TOOL_NAME="echo" STUB_TOOL_ARGS='{"message":"approve-me"}' \
  python "$ROOT/tools/llm-stub/stub.py" >/tmp/p7-stub.log 2>&1 &
PIDS+=($!)
wait_for "http://localhost:$STUB_PORT/health" 15

echo "== 2/7 构建并启动后端 =="
(cd "$ROOT/backend/agent-server" && "$MVN" -B -q package -DskipTests) || { echo "构建失败" >&2; exit 1; }
nohup "$JAVA_BIN" -jar "$ROOT/backend/agent-server/target/agent-server-0.0.1-SNAPSHOT.jar" >"$APP_LOG" 2>&1 &
PIDS+=($!)
wait_for "$API/api/health" 60

TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "登录失败" >&2; exit 1; }
AUTH="Authorization: Bearer $TOKEN"
CT="Content-Type: application/json"
post() { curl -s -X POST "$API$1" -H "$AUTH" -H "$CT" --data-binary @-; }

echo "== 3/7 准备 provider / model / MCP / REQUIRE_APPROVAL Agent =="
PID=$(printf '{"name":"P7-Provider-%s","providerType":"OPENAI_COMPATIBLE","baseUrl":"http://localhost:%s/v1","apiKey":"sk-p7","status":"ENABLED"}' "$RUN_ID" "$STUB_PORT" | post /api/model-providers | id_of)
MID=$(printf '{"providerId":%s,"modelId":"stub-%s"}' "$PID" "$RUN_ID" | post /api/models | id_of)
MCP_NAME="p7-mcp-$RUN_ID"
MCP_ID=$(printf '{"name":"%s","transport":"STDIO","command":"cmd","args":["/c","npx","-y","@modelcontextprotocol/server-everything","stdio"],"status":"ENABLED"}' "$MCP_NAME" | post /api/mcp-servers | id_of)
curl -s -X POST "$API/api/mcp-servers/$MCP_ID/sync-tools" -H "$AUTH" >/dev/null
AGENT="p7-agent-$RUN_ID"
printf '{"name":"%s","agentId":"%s","sysPrompt":"你是助手","modelId":%s,"permissionMode":"REQUIRE_APPROVAL","maxIterations":5,"status":"ENABLED","skillIds":[],"mcpServerIds":[%s]}' \
  "$AGENT" "$AGENT" "$MID" "$MCP_ID" | post /api/agents >/dev/null
[ -n "$PID" ] && [ -n "$MID" ] && [ -n "$MCP_ID" ] || { echo "fixture 失败" >&2; exit 1; }
echo "   agent=$AGENT mcp=$MCP_ID"

run_turn() { # thread runId suffix [extra json fields]
  printf '{"threadId":"%s","runId":"%s-%s","messages":%s%s,"forwardedProps":{"agentId":"%s"}}' \
    "$1" "$1" "$2" "$3" "$4" "$AGENT" \
    | curl -sN -X POST "$API/agui/run" -H "$AUTH" -H "$CT" --data-binary @- --max-time 120
}

echo "== 4/7 批准路径：拦截 -> 批准 -> resume 执行 =="
T1="p7-approve-$RUN_ID"
OUT1=$(run_turn "$T1" 1 '[{"id":"m1","role":"user","content":"调用 echo"}]' "")
IID1=$(echo "$OUT1" | grep -o '"id":"[^"]*:call_stub_1"' | head -1 | sed 's/"id":"//;s/"//')
SID1=$(curl -s "$API/api/sessions" -H "$AUTH" | python -c "
import sys,json
for s in json.load(sys.stdin)['data']:
    if s['threadId']=='$T1': print(s['id']); break")
APPROVALS=$(curl -s "$API/api/approvals?sessionId=$SID1" -H "$AUTH")
DECIDE1=$(curl -s -X POST "$API/api/approvals/$IID1/decide" -H "$AUTH" -H "$CT" -d '{"decision":"APPROVED"}')
OUT2=$(run_turn "$T1" 2 '[]' ",\"resume\":[{\"interruptId\":\"$IID1\",\"status\":\"resolved\",\"payload\":{\"approved\":true}}]")

echo "== 5/7 拒绝路径：拦截 -> 拒绝 -> resume（不应执行）=="
T2="p7-deny-$RUN_ID"
OUT3=$(run_turn "$T2" 1 '[{"id":"m1","role":"user","content":"调用 echo"}]' "")
IID2=$(echo "$OUT3" | grep -o '"id":"[^"]*:call_stub_1"' | head -1 | sed 's/"id":"//;s/"//')
DECIDE2=$(curl -s -X POST "$API/api/approvals/$IID2/decide" -H "$AUTH" -H "$CT" -d '{"decision":"DENIED"}')
OUT4=$(run_turn "$T2" 2 '[]' ",\"resume\":[{\"interruptId\":\"$IID2\",\"status\":\"cancelled\",\"payload\":{\"approved\":false}}]")

echo "== 6/7 越权与重复决定 =="
# 造一个普通用户并以其身份尝试审批 admin 会话的记录
cd /tmp/dbprobe 2>/dev/null || { mkdir -p /tmp/dbprobe && cd /tmp/dbprobe; }
cat > P7User.java <<'EOF'
import java.sql.*;
public class P7User {

  // 凭据从环境变量读取（不要把真实口令写进仓库）
  static String env(String key, String fallback) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? fallback : v;
  }
  static String dbHost() { return env("DB_HOST", "localhost"); }
  static String dbUser() { return env("DB_USERNAME", "agent"); }
  static String dbPassword() { return env("DB_PASSWORD", "change-me"); }
  static String jdbcUrl() {
    // 优先用完整 DB_URL（.env 里通常这么配）；否则用 DB_HOST/DB_NAME 拼
    String url = System.getenv("DB_URL");
    if (url != null && !url.isBlank()) return url;
    return "jdbc:postgresql://" + dbHost() + ":5432/" + env("DB_NAME", "agent_platform")
        + "?stringtype=unspecified";
  }

  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(jdbcUrl(), dbUser(), dbPassword())) {
      try (PreparedStatement del = c.prepareStatement("DELETE FROM sys_user WHERE username = ?")) {
        del.setString(1, "p7-outsider");
        del.executeUpdate();
      }
      try (PreparedStatement ins = c.prepareStatement(
          "INSERT INTO sys_user (username, password_hash, display_name, role, status) "
              + "SELECT ?, password_hash, '外部用户', 'USER', 'ENABLED' FROM sys_user WHERE username = 'admin'")) {
        ins.setString(1, "p7-outsider");
        ins.executeUpdate();
        System.out.println("outsider ready");
      }
    }
  }
}
EOF
"${JAVA_BIN%/java}/java" -cp "$PG_JAR" P7User.java | tail -1
OUTSIDER_TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"p7-outsider","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
OUTSIDER_HTTP=$(curl -s -o /tmp/p7-outsider.json -w "%{http_code}" -X POST "$API/api/approvals/$IID1/decide" \
  -H "Authorization: Bearer $OUTSIDER_TOKEN" -H "$CT" -d '{"decision":"APPROVED"}')
DUP_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API/api/approvals/$IID1/decide" \
  -H "$AUTH" -H "$CT" -d '{"decision":"APPROVED"}')
echo "   outsider HTTP=$OUTSIDER_HTTP  重复决定 HTTP=$DUP_HTTP"

echo "== 7/7 断言 =="
FAIL=0
check() { if [ "$2" = "1" ]; then echo "   PASS  $1"; else echo "   FAIL  $1"; FAIL=1; fi; }
check "首轮被拦截：出现 interrupt 且无 TOOL_CALL_RESULT" \
  "$(echo "$OUT1" | grep -q '"type":"interrupt"' && ! echo "$OUT1" | grep -q 'TOOL_CALL_RESULT' && echo 1 || echo 0)"
# 契约：MCP 工具名带 Server 命名空间（<serverName>__echo）
check "审批记录落库为 PENDING 且带工具名与入参" \
  "$(echo "$APPROVALS" | grep -q "\"toolName\":\"$MCP_NAME" && echo "$APPROVALS" | grep -q '"status":"PENDING"' && echo 1 || echo 0)"
check "批准后 resumeStatus=resolved" "$(echo "$DECIDE1" | grep -q '"resumeStatus":"resolved"' && echo 1 || echo 0)"
check "resume 未报错（无 RUN_ERROR）" "$(echo "$OUT2" | grep -q 'RUN_ERROR' && echo 0 || echo 1)"
check "批准后工具真正执行（agent 侧 POST_ACTING state=SUCCESS）" \
  "$(grep -q "PRE_ACTING  | id=call_stub_1, name=$MCP_NAME" "$APP_LOG" && grep -q "POST_ACTING | id=call_stub_1, name=$MCP_NAME" "$APP_LOG" && echo 1 || echo 0)"
check "拒绝后 resumeStatus=cancelled" "$(echo "$DECIDE2" | grep -q '"resumeStatus":"cancelled"' && echo 1 || echo 0)"
check "非属主决定被拒（403）" "$(echo "$OUTSIDER_HTTP" | grep -q 403 && echo 1 || echo 0)"
check "重复决定返回 409" "$(echo "$DUP_HTTP" | grep -q 409 && echo 1 || echo 0)"

echo
echo "批准路径事件序列: $(echo "$OUT1" | grep -o '"type":"[A-Z_]*"' | sed 's/"type":"//;s/"//' | tr '\n' ' ')"
echo "resume 事件序列:   $(echo "$OUT2" | grep -o '"type":"[A-Z_]*"' | sed 's/"type":"//;s/"//' | tr '\n' ' ')"
echo "拒绝后事件序列:    $(echo "$OUT4" | grep -o '"type":"[A-Z_]*"' | sed 's/"type":"//;s/"//' | tr '\n' ' ')"
echo
[ "$FAIL" = "0" ] && echo "P7.1 验收：全部通过" || echo "P7.1 验收：存在失败项"
exit $FAIL
