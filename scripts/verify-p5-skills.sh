#!/usr/bin/env bash
# Task 5.1 验收脚本：Skill CRUD + 官方仓库同步 + Agent 绑定生效（可复跑）。
#
# 关键设计：AgentScope 对 Skill 采用「渐进式披露」——
#   系统提示词里注入 <available_skills>（名称 + 描述 + skill-id），
#   内容在模型需要时再按需加载。因此这里分三层取证：
#   a) 绑定了 Skill 的 Agent：模型请求里出现该 Skill 的名称/描述（且桩据此回标记）
#   b) 未绑定的对照 Agent：模型请求里不出现
#   c) 官方仓库 agentscope.agentscope_skills 里存有完整内容
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
SKILL_NAME="P5-Skill-$RUN_ID"
DESC_MARK="DESC-MARK-$RUN_ID"
CONTENT_MARK="CONTENT-MARK-$RUN_ID"
DUMP=/tmp/p5-dump-$RUN_ID.json
PIDS=()

cleanup() { for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done; sleep 1; }
trap cleanup EXIT

free_port() {
  local pids
  pids=$(netstat -ano 2>/dev/null | grep LISTENING | grep ":$1 " | awk '{print $5}' | sort -u)
  for pid in $pids; do [ -n "$pid" ] && taskkill //PID "$pid" //F >/dev/null 2>&1 || true; done
}
wait_for() { for _ in $(seq 1 "${2:-60}"); do [ -n "$(curl -s -m 2 "$1" || true)" ] && return 0; sleep 2; done; echo "等待超时: $1" >&2; return 1; }

echo "== 0/6 清理端口 =="
free_port 5173; free_port 8080; free_port "$STUB_PORT"; sleep 2

echo "== 1/6 启动模型桩（标记 $DESC_MARK -> SKILL-SEEN）=="
rm -f "$DUMP"
STUB_DUMP="$DUMP" STUB_MARKER="$DESC_MARK" STUB_MARKER_REPLY="SKILL-SEEN" \
  python "$ROOT/tools/llm-stub/stub.py" >/tmp/p5-stub.log 2>&1 &
PIDS+=($!)
wait_for "http://localhost:$STUB_PORT/health" 15

echo "== 2/6 构建并启动后端 =="
(cd "$ROOT/backend/agent-server" && "$MVN" -B -q package -DskipTests) || { echo "构建失败" >&2; exit 1; }
nohup "$JAVA_BIN" -jar "$ROOT/backend/agent-server/target/agent-server-0.0.1-SNAPSHOT.jar" >/tmp/p5-app.log 2>&1 &
PIDS+=($!)
wait_for "$API/api/health" 60

TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "登录失败" >&2; exit 1; }
AUTH="Authorization: Bearer $TOKEN"
CT="Content-Type: application/json"
post() { curl -s -X POST "$API$1" -H "$AUTH" -H "$CT" --data-binary @-; }
id_of() { sed -n 's/.*"id":\([0-9]*\).*/\1/p'; }

echo "== 3/6 准备 provider / model / skill / 两个 Agent =="
PID=$(printf '{"name":"P5-Provider-%s","providerType":"OPENAI_COMPATIBLE","baseUrl":"http://localhost:%s/v1","apiKey":"sk-p5","status":"ENABLED"}' "$RUN_ID" "$STUB_PORT" | post /api/model-providers | id_of)
MID=$(printf '{"providerId":%s,"modelId":"stub-model-%s"}' "$PID" "$RUN_ID" | post /api/models | id_of)
SID=$(printf '{"name":"%s","description":"验收技能，标识 %s","content":"回答时在末尾加【OK】。内容标识 %s","status":"ENABLED"}' "$SKILL_NAME" "$DESC_MARK" "$CONTENT_MARK" | post /api/skills | id_of)
[ -n "$PID" ] && [ -n "$MID" ] && [ -n "$SID" ] || { echo "fixture 创建失败: pid=$PID mid=$MID sid=$SID" >&2; exit 1; }

BOUND_AGENT="p5-bound-$RUN_ID"
UNBOUND_AGENT="p5-unbound-$RUN_ID"
printf '{"name":"%s","agentId":"%s","sysPrompt":"你是助手","modelId":%s,"permissionMode":"ALWAYS_ALLOW","maxIterations":3,"status":"ENABLED","skillIds":[%s],"mcpServerIds":[]}' "$BOUND_AGENT" "$BOUND_AGENT" "$MID" "$SID" | post /api/agents >/dev/null
printf '{"name":"%s","agentId":"%s","sysPrompt":"你是助手","modelId":%s,"permissionMode":"ALWAYS_ALLOW","maxIterations":3,"status":"ENABLED","skillIds":[],"mcpServerIds":[]}' "$UNBOUND_AGENT" "$UNBOUND_AGENT" "$MID" | post /api/agents >/dev/null
echo "   provider=$PID model=$MID skill=$SID($SKILL_NAME)"

echo "== 4/6 对话：绑定 Skill vs 未绑定（对照）=="
run() { # agentId thread
  printf '{"threadId":"%s","runId":"%s-1","messages":[{"id":"m1","role":"user","content":"请回复"}],"forwardedProps":{"agentId":"%s"}}' "$2" "$2" "$1" \
    | curl -sN -X POST "$API/agui/run" -H "$AUTH" -H "$CT" --data-binary @- --max-time 60
}
BOUND_OUT=$(run "$BOUND_AGENT" "p5-bound-$RUN_ID")
UNBOUND_OUT=$(run "$UNBOUND_AGENT" "p5-unbound-$RUN_ID")
echo "$BOUND_OUT"   | grep -o '"delta":"[^"]*"' | tr '\n' ' '; echo "   <- 绑定 Skill"
echo "$UNBOUND_OUT" | grep -o '"delta":"[^"]*"' | tr '\n' ' '; echo "   <- 未绑定（对照）"

# 流式分片会把标记拆开，先拼成完整回复再断言（等同前端渲染结果）
join_deltas() { grep -o '"delta":"[^"]*"' | sed 's/"delta":"//; s/"$//' | tr -d '\n'; }
BOUND_TEXT=$(echo "$BOUND_OUT" | join_deltas)
UNBOUND_TEXT=$(echo "$UNBOUND_OUT" | join_deltas)
echo "   拼接后 -> 绑定: [$BOUND_TEXT] | 对照: [$UNBOUND_TEXT]"

echo "== 5/6 断言 =="
FAIL=0
check() { # desc, condition
  if [ "$2" = "1" ]; then echo "   PASS  $1"; else echo "   FAIL  $1"; FAIL=1; fi
}
check "绑定 Agent 的模型请求里出现 Skill 名称" "$(grep -q "$SKILL_NAME" "$DUMP" && echo 1 || echo 0)"
check "模型请求里出现 Skill 描述（渐进式披露的元数据）" "$(grep -q "$DESC_MARK" "$DUMP" && echo 1 || echo 0)"
check "绑定 Agent 回复带上桩标记 SKILL-SEEN（端到端）" "$(echo "$BOUND_TEXT" | grep -q 'SKILL-SEEN' && echo 1 || echo 0)"
check "未绑定 Agent 回复不含 SKILL-SEEN" "$(echo "$UNBOUND_TEXT" | grep -q 'SKILL-SEEN' && echo 0 || echo 1)"

echo "== 6/6 官方仓库中存有完整 Skill 内容 =="
cd /tmp/dbprobe 2>/dev/null || { mkdir -p /tmp/dbprobe && cd /tmp/dbprobe; }
cat > P5Check.java <<EOF
import java.sql.*;
public class P5Check {

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
    try (Connection c = DriverManager.getConnection(jdbcUrl(), dbUser(), dbPassword());
         PreparedStatement ps = c.prepareStatement(
             "SELECT count(*) FROM agentscope.agentscope_skills WHERE name = ? AND skill_content LIKE ?")) {
      ps.setString(1, "$SKILL_NAME");
      ps.setString(2, "%$CONTENT_MARK%");
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        System.out.println("agentscope_skills 命中行数 = " + rs.getInt(1));
        System.out.println(rs.getInt(1) == 1 ? "PASS  官方仓库存有完整内容" : "FAIL  官方仓库未找到内容");
      }
    }
  }
}
EOF
"${JAVA_BIN%/java}/java" -cp "$PG_JAR" P5Check.java | tee /tmp/p5-db.txt
grep -q "PASS" /tmp/p5-db.txt || FAIL=1

echo
[ "$FAIL" = "0" ] && echo "P5 验收：全部通过" || echo "P5 验收：存在失败项"
exit $FAIL
