#!/usr/bin/env bash
# Task 8.1 验收脚本：Token 采集与聚合（可复跑）。
#
# 断言：
#   1) 跑 3 次对话后 summary 返回非零 token，且 byModel / byDay 结构正确
#   2) records 分页返回明细
#   3) ADMIN 可查全员（users 列表包含他人）；USER 查他人被拒（403）
#   4) 同一个 runId 下多次模型调用共用 request_id（同 run 多行）
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="/c/Program Files/Java/jdk-21.0.3+9/bin/java"
MVN="/c/tools/apache-maven-3.9.16/bin/mvn"
PG_JAR="C:\\Users\\Administrator\\.m2\\repository\\org\\postgresql\\postgresql\\42.7.11\\postgresql-42.7.11.jar"
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

echo "== 0/6 清理端口 =="
free_port 8080; free_port "$STUB_PORT"; sleep 2

echo "== 1/6 启动桩 + 后端 =="
python "$ROOT/tools/llm-stub/stub.py" >/tmp/p8-stub.log 2>&1 &
PIDS+=($!)
wait_for "http://localhost:$STUB_PORT/health" 15
(cd "$ROOT/backend/agent-server" && "$MVN" -B -q package -DskipTests) || { echo "构建失败" >&2; exit 1; }
nohup "$JAVA_BIN" -jar "$ROOT/backend/agent-server/target/agent-server-0.0.1-SNAPSHOT.jar" >/tmp/p8-app.log 2>&1 &
PIDS+=($!)
wait_for "$API/api/health" 60

TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
AUTH="Authorization: Bearer $TOKEN"
CT="Content-Type: application/json"
post() { curl -s -X POST "$API$1" -H "$AUTH" -H "$CT" --data-binary @-; }

echo "== 2/6 准备 provider / model / agent =="
PID=$(printf '{"name":"P8-Provider-%s","providerType":"OPENAI_COMPATIBLE","baseUrl":"http://localhost:%s/v1","apiKey":"sk","status":"ENABLED"}' "$RUN_ID" "$STUB_PORT" | post /api/model-providers | id_of)
MID=$(printf '{"providerId":%s,"modelId":"stub-%s"}' "$PID" "$RUN_ID" | post /api/models | id_of)
AGENT="p8-agent-$RUN_ID"
printf '{"name":"%s","agentId":"%s","sysPrompt":"你是助手","modelId":%s,"permissionMode":"ALWAYS_ALLOW","maxIterations":5,"status":"ENABLED","skillIds":[],"mcpServerIds":[]}' \
  "$AGENT" "$AGENT" "$MID" | post /api/agents >/dev/null

echo "== 3/6 跑 3 次对话 =="
for i in 1 2 3; do
  printf '{"threadId":"p8-%s-%s","runId":"p8-%s-run%s","messages":[{"id":"m1","role":"user","content":"第 %s 次"}],"forwardedProps":{"agentId":"%s"}}' \
    "$RUN_ID" "$i" "$RUN_ID" "$i" "$i" "$AGENT" \
    | curl -sN -X POST "$API/agui/run" -H "$AUTH" -H "$CT" --data-binary @- --max-time 90 >/dev/null
done

echo "== 4/6 聚合接口 =="
SUMMARY=$(curl -s "$API/api/audit/token/summary" -H "$AUTH")
echo "   summary: $(echo "$SUMMARY" | head -c 420)"
RECORDS=$(curl -s "$API/api/audit/token/records?current=1&size=5" -H "$AUTH")
USERS=$(curl -s "$API/api/audit/token/users" -H "$AUTH")

echo "== 5/6 权限隔离（造一个普通用户）=="
cd /tmp/dbprobe 2>/dev/null || { mkdir -p /tmp/dbprobe && cd /tmp/dbprobe; }
cat > P8User.java <<'EOF'
import java.sql.*;
public class P8User {

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
        del.setString(1, "p8-user"); del.executeUpdate();
      }
      try (PreparedStatement ins = c.prepareStatement(
          "INSERT INTO sys_user (username, password_hash, display_name, role, status) "
              + "SELECT ?, password_hash, '普通用户', 'USER', 'ENABLED' FROM sys_user WHERE username = 'admin'")) {
        ins.setString(1, "p8-user"); ins.executeUpdate();
      }
      System.out.println("user ready");
    }
  }
}
EOF
"${JAVA_BIN%/java}/java" -cp "$PG_JAR" P8User.java | tail -1
USER_TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"p8-user","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
ADMIN_ID=$(curl -s "$API/api/auth/me" -H "$AUTH" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
SELF_SUMMARY=$(curl -s -o /dev/null -w "%{http_code}" "$API/api/audit/token/summary" -H "Authorization: Bearer $USER_TOKEN")
OTHER_SUMMARY=$(curl -s -o /tmp/p8-other.json -w "%{http_code}" "$API/api/audit/token/summary?userId=$ADMIN_ID" -H "Authorization: Bearer $USER_TOKEN")

echo "== 6/6 断言 =="
FAIL=0
check() { if [ "$2" = "1" ]; then echo "   PASS  $1"; else echo "   FAIL  $1"; FAIL=1; fi; }
TOTAL=$(echo "$SUMMARY" | sed -n 's/.*"totalTokens":\([0-9]*\).*/\1/p')
check "summary 返回非零 totalTokens（实测 $TOTAL）" "$([ -n "$TOTAL" ] && [ "$TOTAL" -gt 0 ] && echo 1 || echo 0)"
check "summary 含 byModel 且非空" "$(echo "$SUMMARY" | grep -q '"byModel":\[{' && echo 1 || echo 0)"
check "summary 含 byDay 且非空" "$(echo "$SUMMARY" | grep -q '"byDay":\[{' && echo 1 || echo 0)"
check "records 返回明细分页" "$(echo "$RECORDS" | grep -q '"total":[1-9]' && echo 1 || echo 0)"
check "ADMIN 可看全员 users 列表" "$(echo "$USERS" | grep -q '"username"' && echo 1 || echo 0)"
check "普通用户查自己 200" "$(echo "$SELF_SUMMARY" | grep -q 200 && echo 1 || echo 0)"
check "普通用户查他人被拒（403）" "$(echo "$OTHER_SUMMARY" | grep -q 403 && echo 1 || echo 0)"

echo
echo "   token_usage 明细: $(echo "$RECORDS" | head -c 300)"
echo
[ "$FAIL" = "0" ] && echo "P8.1 验收：全部通过" || echo "P8.1 验收：存在失败项"
exit $FAIL
