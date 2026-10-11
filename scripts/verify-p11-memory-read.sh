#!/usr/bin/env bash
# Task 11.1 关键证据：库是否为长期记忆的**有效存储**（而不只是旁路副本）。
#
# 判据（纯数据库，不依赖模型是否把记忆注入提示词）：
#   1) 直接往 memory_store 写一段带标记的记忆（模拟"此前已记住的事"）
#   2) 删掉工作区 memory/ 下所有文件
#   3) 再跑一轮，触发一次新的 Memory Flush（flush 前框架会读已有记忆再追加）
#   4) 看库里那条记忆**是否仍含原标记**：
#        含 → 读取来自数据库（库是有效存储）
#        不含（被覆盖成单次 flush）→ 读取仍来自磁盘，需要退到备选方案
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
MARK="MEMO-MARKER-$(date +%s)"

free_port 8080; free_port "$STUB_PORT"; sleep 2
echo "== 1/5 启动桩 + 后端 =="
STUB_REPLY_FROM_MODEL=1 PORT="$STUB_PORT" nohup python "$ROOT/tools/llm-stub/stub.py" >/tmp/p11c-stub.log 2>&1 &
PIDS+=($!)
wait_for "http://localhost:$STUB_PORT/health" 15
nohup "$JAVA_BIN" -jar "$ROOT/backend/agent-server/target/agent-server-0.0.1-SNAPSHOT.jar" >/tmp/p11c-app.log 2>&1 &
PIDS+=($!)
wait_for "$API/api/health" 90 || { echo "后端未起来" >&2; exit 1; }

TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
AUTH="Authorization: Bearer $TOKEN"; CT="Content-Type: application/json"
post() { curl -s -X POST "$API$1" -H "$AUTH" -H "$CT" --data-binary @-; }

echo "== 2/5 往 memory_store 种一段带标记的记忆（标记 $MARK）=="
cd /tmp/dbprobe
cat > Seed.java <<'JAVAEOF'
import java.sql.*;
public class Seed {
  public static void main(String[] a) throws Exception {
    String mark = System.getenv("MARK");
    String payload = "{\"content\": \"## Memory Flush " + mark + " 用户的长期记忆：项目在 D:/project/my-agent\"}";
    String url = "jdbc:postgresql://localhost:5432/agent_platform";
    try (Connection c = DriverManager.getConnection(url, "app_user", "CHANGE_ME");
         PreparedStatement p = c.prepareStatement(
             "insert into memory_store (namespace_path, item_key, payload, version, created_at, updated_at) "
             + "values ('user/1', '/2026-10-11.md', ?::jsonb, 1, now(), now()) "
             + "on conflict (namespace_path, item_key) do update set payload = excluded.payload, "
             + "version = memory_store.version + 1, updated_at = now()")) {
      p.setString(1, payload);
      p.executeUpdate();
    }
    try (Connection c = DriverManager.getConnection(url, "app_user", "CHANGE_ME");
         Statement s = c.createStatement();
         ResultSet r = s.executeQuery(
             "select item_key, payload::text like '%" + mark + "%' as has_marker from memory_store")) {
      while (r.next()) System.out.println("   种子后：key=" + r.getString(1) + " 含标记=" + r.getBoolean(2));
    }
  }
}
JAVAEOF
MARK="$MARK" "$JDK/java" -cp "$JAR" Seed.java 2>&1 | head -4

echo "== 3/5 删掉磁盘 memory 文件 =="
rm -f "$ROOT/.myagent-workspace/1/memory"/*.md
echo "   磁盘文件数: $(ls "$ROOT/.myagent-workspace/1/memory" 2>/dev/null | wc -l)"

echo "== 4/5 再跑一轮（触发新的 flush）=="
RUN_ID=$(date +%s)
PID=$(printf '{"name":"P11c-Provider-%s","providerType":"OPENAI_COMPATIBLE","baseUrl":"http://localhost:%s/v1","apiKey":"k","status":"ENABLED"}' "$RUN_ID" "$STUB_PORT" | post /api/model-providers | id_of)
MID=$(printf '{"providerId":%s,"modelId":"p11c-model-%s"}' "$PID" "$RUN_ID" | post /api/models | id_of)
AG="p11c-agent-$RUN_ID"
printf '{"name":"%s","agentId":"%s","sysPrompt":"You are a helper.","modelId":%s,"permissionMode":"ALWAYS_ALLOW","maxIterations":3,"status":"ENABLED","skillIds":[],"mcpServerIds":[]}' "$AG" "$AG" "$MID" | post /api/agents >/dev/null
AID=$(curl -s "$API/api/agents?current=1&size=300" -H "$AUTH" | AG="$AG" python -c "
import os,sys,json
try:
    data=json.load(sys.stdin)['data']['records']
except Exception:
    data=[]
for a in data:
    if a['agentId']==os.environ['AG']:
        print(a['id']); break")
SID=$(printf '{"agentId":%s,"title":"p11c"}' "$AID" | post /api/sessions | id_of)
THREAD=$(curl -s "$API/api/sessions" -H "$AUTH" | S="$SID" python -c "
import os,sys,json
try:
    data=json.load(sys.stdin)['data']
except Exception:
    data=[]
for s in data:
    if s['id']==int(os.environ['S']):
        print(s['threadId']); break")
[ -n "$THREAD" ] || { echo "会话创建失败" >&2; exit 1; }
printf '{"threadId":"%s","runId":"p11c-%s","messages":[{"id":"m1","role":"user","content":"再说一件事：我今天在调这个平台"}],"forwardedProps":{"agentId":"%s"}}' \
  "$THREAD" "$RUN_ID" "$AG" | curl -sN -X POST "$API/agui/run" -H "$AUTH" -H "$CT" --data-binary @- --max-time 120 | grep -o '"type":"RUN_FINISHED"' | head -1
echo "   等 flush…"; sleep 18

echo "== 5/5 判据 =="
cat > Check.java <<'JAVAEOF'
import java.sql.*;
public class Check {
  public static void main(String[] a) throws Exception {
    String mark = System.getenv("MARK");
    String url = "jdbc:postgresql://localhost:5432/agent_platform";
    try (Connection c = DriverManager.getConnection(url, "app_user", "CHANGE_ME");
         Statement s = c.createStatement()) {
      ResultSet r = s.executeQuery("select item_key, payload::text like '%" + mark
          + "%' as has_marker, length(payload::text) as bytes from memory_store order by id");
      while (r.next()) {
        System.out.println("   memory_store: key=" + r.getString(1)
            + " 仍含原标记=" + r.getBoolean(2) + " bytes=" + r.getInt(3));
      }
      ResultSet r2 = s.executeQuery("select count(*) from agent_state");
      r2.next();
      System.out.println("   agent_state 行数=" + r2.getInt(1));
    }
  }
}
JAVAEOF
MARK="$MARK" "$JDK/java" -cp "$JAR" Check.java 2>&1 | head -5
echo "   磁盘 memory 文件数（库为唯一存储时应为 0）: $(ls "$ROOT/.myagent-workspace/1/memory" 2>/dev/null | wc -l)"
echo
echo "P11.1 关键证据实验完成"
