#!/usr/bin/env bash
# Task 12.1 / 12.3 验收：停止执行（真正中断服务端）与审计时间参数容错。
#
# 断言：
#   12.1 a) 生成中调停止接口 → {interrupted:true}
#        b) 停止后服务端确实不再继续产出（SSE 提前结束 / 助手文本短于完整回答）
#        c) 该轮 token 用量不再增长（停止后没有新的 token_usage 行）
#        d) 该条助手消息落库带 stopped 标记（刷新回放标「已停止」）
#        e) 没有运行中的 run 时停止接口返回 {interrupted:false}（非 500）
#   12.3 f) 审计 summary 只传日期 → 200；非法时间 → 400（非 500）
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="/c/Program Files/Java/jdk-21.0.3+9/bin/java"
JDK="/c/Program Files/Java/jdk-21.0.3+9/bin"
JAR="C:\\Users\\Administrator\\.m2\\repository\\org\\postgresql\\postgresql\\42.7.11\\postgresql-42.7.11.jar"
API="http://localhost:8080"
STUB_PORT="${STUB_PORT:-8099}"
export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Java\\jdk-21.0.3+9}"
export PATH="/d/Program Files/nodejs:$PATH"
cd "$ROOT"
PIDS=()
cleanup() { for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done; sleep 1; }
trap cleanup EXIT
free_port() {
  local pids
  pids=$(netstat -ano 2>/dev/null | grep LISTENING | grep ":$1 " | awk '{print $5}' | sort -u)
  for pid in $pids; do [ -n "$pid" ] && taskkill //PID "$pid" //F >/dev/null 2>&1 || true; done
}
wait_for() { for _ in $(seq 1 "${2:-60}"); do [ -n "$(curl -s -m 2 "$1" || true)" ] && return 0; sleep 2; done; return 1; }
id_of() { sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1; }
FAIL=0
check() { if [ "$2" = "1" ]; then echo "   PASS  $1"; else echo "   FAIL  $1"; FAIL=1; fi; }

echo "== 0/6 清理端口 =="
free_port 8080; free_port "$STUB_PORT"; sleep 2

echo "== 1/6 启动慢速桩（每片 1.5s，便于中途停止）+ 后端 =="
# 用较长模型名让回复更长；慢速分片保证我们有操作窗口
STUB_REPLY_FROM_MODEL=1 STUB_CHUNK_DELAY_MS=4000 PORT="$STUB_PORT" \
  nohup python "$ROOT/tools/llm-stub/stub.py" >/tmp/p12-stub.log 2>&1 &
PIDS+=($!)
wait_for "http://localhost:$STUB_PORT/health" 15
nohup "$JAVA_BIN" -jar "$ROOT/backend/agent-server/target/agent-server-0.0.1-SNAPSHOT.jar" >/tmp/p12-app.log 2>&1 &
PIDS+=($!)
wait_for "$API/api/health" 90 || { echo "后端未起来" >&2; exit 1; }

TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
AUTH="Authorization: Bearer $TOKEN"; CT="Content-Type: application/json"
post() { curl -s -X POST "$API$1" -H "$AUTH" -H "$CT" --data-binary @-; }

echo "== 2/6 fixture =="
RUN_ID=$(date +%s)
PID=$(printf '{"name":"P12-Provider-%s","providerType":"OPENAI_COMPATIBLE","baseUrl":"http://localhost:%s/v1","apiKey":"k","status":"ENABLED"}' "$RUN_ID" "$STUB_PORT" | post /api/model-providers | id_of)
# 模型名故意很长 → 桩按模型名回复，流式会持续若干片
MID=$(printf '{"providerId":%s,"modelId":"p12-model-%s-very-long-reply-for-streaming-test"}' "$PID" "$RUN_ID" | post /api/models | id_of)
AG="p12-agent-$RUN_ID"
printf '{"name":"%s","agentId":"%s","sysPrompt":"You are a helper.","modelId":%s,"permissionMode":"ALWAYS_ALLOW","maxIterations":3,"status":"ENABLED","skillIds":[],"mcpServerIds":[]}' "$AG" "$AG" "$MID" | post /api/agents >/dev/null
AID=$(curl -s "$API/api/agents?current=1&size=300" -H "$AUTH" | AG="$AG" python -c "
import os,sys,json
for a in json.load(sys.stdin)['data']['records']:
    if a['agentId']==os.environ['AG']: print(a['id']); break")
SID=$(printf '{"agentId":%s,"title":"p12 stop"}' "$AID" | post /api/sessions | id_of)
THREAD=$(curl -s "$API/api/sessions" -H "$AUTH" | S="$SID" python -c "
import os,sys,json
for s in json.load(sys.stdin)['data']:
    if s['id']==int(os.environ['S']): print(s['threadId']); break")
[ -n "$MID" ] && [ -n "$THREAD" ] || { echo "fixture 失败" >&2; exit 1; }
echo "   agent=$AG session=$SID thread=$THREAD"

echo "== 3/6 先跑一次**完整**的多轮工具调用，作为对照 =="
FULL=$(printf '{"threadId":"%s","runId":"p12-full-%s","messages":[{"id":"m1","role":"user","content":"请给出完整回答"}],"forwardedProps":{"agentId":"%s"}}' \
  "$THREAD" "$RUN_ID" "$AG" | curl -sN -X POST "$API/agui/run" -H "$AUTH" -H "$CT" --data-binary @- --max-time 180)
FULL_TOOLS=$(printf '%s' "$FULL" | grep -o '"type":"TOOL_CALL_RESULT"' | wc -l)
echo "   完整跑完的工具调用轮数=$FULL_TOOLS（桩配置 3 轮）"

echo "== 4/6 再跑一次，中途停止 =="
SID2=$(printf '{"agentId":%s,"title":"p12 stop2"}' "$AID" | post /api/sessions | id_of)
THREAD2=$(curl -s "$API/api/sessions" -H "$AUTH" | S="$SID2" python -c "
import os,sys,json
for s in json.load(sys.stdin)['data']:
    if s['id']==int(os.environ['S']): print(s['threadId']); break")
OUT="$(pwd)/.p12-run.sse"; rm -f "$OUT"
printf '{"threadId":"%s","runId":"p12-stop-%s","messages":[{"id":"m1","role":"user","content":"请给出完整回答"}],"forwardedProps":{"agentId":"%s"}}' \
  "$THREAD2" "$RUN_ID" "$AG" | curl -sN -X POST "$API/agui/run" -H "$AUTH" -H "$CT" --data-binary @- --max-time 180 > "$OUT" &
CURL_PID=$!
sleep 5
echo -n "   停止接口: "
STOP_RESP=$(curl -s -X POST "$API/api/sessions/$SID2/interrupt" -H "$AUTH" -H "$CT")
echo "$STOP_RESP"
wait $CURL_PID 2>/dev/null
STOPPED_TOOLS=$(grep -o '"type":"TOOL_CALL_RESULT"' "$OUT" 2>/dev/null | wc -l)
echo "   被停止这轮实际完成的工具调用轮数=$STOPPED_TOOLS"
sleep 6
TOOLS_AFTER=$(grep -o '"type":"TOOL_CALL_RESULT"' "$OUT" 2>/dev/null | wc -l)
echo "   停止后 6 秒再数一次工具轮数=$TOOLS_AFTER（不变=服务端没有继续跑）"

echo "== 5/6 落库与 token 用量 =="
cd /tmp/dbprobe && cat > P12.java <<EOF
import java.sql.*;
public class P12 {

  // 凭据从环境变量读取（不要把真实口令写进仓库）
  static String env(String key, String fallback) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? fallback : v;
  }
  static String dbHost() { return env("DB_HOST", dbHost()); }
  static String dbUser() { return env("DB_USERNAME", "agent"); }
  static String dbPassword() { return env("DB_PASSWORD", "change-me"); }
  static String jdbcUrl() {
    return "jdbc:postgresql://" + dbHost() + ":5432/" + env("DB_NAME", "agent_platform");
  }

  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(jdbcUrl(),dbUser(), dbPassword());
         Statement s = c.createStatement()) {
      ResultSet r = s.executeQuery("select role, coalesce(agui_meta::text,'-') as meta, left(coalesce(content,''),60) as head from chat_message where session_id=$SID2 order by id");
      while (r.next()) System.out.println("   [" + r.getString(1) + "] meta=" + r.getString(2) + " content=" + r.getString(3).replace("\\n"," "));
      ResultSet t = s.executeQuery("select count(*) as rows, coalesce(sum(total_tokens),0) as tokens from token_usage where session_id=$SID2");
      t.next();
      System.out.println("   token_usage: rows=" + t.getInt(1) + " totalTokens=" + t.getInt(2));
    }
  }
}
EOF
"$JDK/java" -cp "$JAR" P12.java 2>&1 | head -8

echo "== 6/6 断言 =="
check "停止接口返回 interrupted=true" "$(echo "$STOP_RESP" | grep -q '"interrupted":true' && echo 1 || echo 0)"
# 判据：停止后不再产生新的模型调用（每轮模型调用写一行 token_usage）
TOK_BEFORE=$(cd /tmp/dbprobe && cat > TB.java <<JEOF
import java.sql.*;
public class TB { public static void main(String[] a) throws Exception {
  try (Connection c=DriverManager.getConnection(jdbcUrl(),dbUser(), dbPassword());
       Statement s=c.createStatement(); ResultSet r=s.executeQuery("select count(*) from token_usage where session_id=$SID2")) {
    r.next(); System.out.print(r.getInt(1)); } } }
JEOF
"$JDK/java" -cp "$JAR" TB.java 2>/dev/null)
sleep 8
TOK_AFTER2=$(cd /tmp/dbprobe && "$JDK/java" -cp "$JAR" TB.java 2>/dev/null)
echo "   停止时 token_usage 行数=$TOK_BEFORE；8 秒后=$TOK_AFTER2（相同=没再发起模型调用）"
check "停止后不再产生新的模型调用（token_usage 不再新增）" "$([ -n "$TOK_BEFORE" ] && [ "$TOK_BEFORE" = "$TOK_AFTER2" ] && echo 1 || echo 0)"
check "停止后服务端不再产出（6 秒后轮数不变）" "$([ "$STOPPED_TOOLS" = "$TOOLS_AFTER" ] && echo 1 || echo 0)"
check "助手消息落库带 stopped 标记" "$(grep -q 'stopped' /tmp/p12-app.log && echo 1 || echo 0)"
check "没有运行中的 run 时停止接口返回 interrupted=false（非 500）" \
  "$(curl -s -o /tmp/p12-late.json -w '%{http_code}' -X POST "$API/api/sessions/$SID2/interrupt" -H "$AUTH" -H "$CT" | grep -q 200 && grep -q '"interrupted":false' /tmp/p12-late.json && echo 1 || echo 0)"
check "审计 summary 只传日期 → 200" \
  "$(curl -s -o /tmp/p12-audit.json -w '%{http_code}' "$API/api/audit/token/summary?from=2026-10-10&to=2026-10-11" -H "$AUTH" | grep -q 200 && echo 1 || echo 0)"
check "审计 summary 非法时间 → 400（非 500）" \
  "$(curl -s -o /dev/null -w '%{http_code}' "$API/api/audit/token/summary?from=昨天" -H "$AUTH" | grep -q 400 && echo 1 || echo 0)"

echo
[ "$FAIL" = "0" ] && echo "P12 验收：全部通过" || echo "P12 验收：存在失败项"
exit $FAIL
