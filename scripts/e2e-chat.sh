#!/usr/bin/env bash
# Task 4.3 浏览器级验收脚本（可复跑）。
#
# 依赖：本机 JDK 21、Maven、Node、Python3、Chrome。
# 用法：bash scripts/e2e-chat.sh
#
# 流程：启动模型桩 -> 启动后端 -> 建 provider/model/agent -> 启动 Vite -> 跑 Playwright -> 清理。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Java\\jdk-21.0.3+9}"
JAVA_BIN="/c/Program Files/Java/jdk-21.0.3+9/bin/java"
MVN="/c/tools/apache-maven-3.9.16/bin/mvn"
NODE_DIR="/d/Program Files/nodejs"
API="http://localhost:8080"
STUB_PORT="${STUB_PORT:-8099}"

export JAVA_HOME
export PATH="$NODE_DIR:/c/tools/apache-maven-3.9.16/bin:$PATH"

echo "== 0/5 清理旧实例与端口 =="
free_port() { # port
  local pids
  pids=$(netstat -ano 2>/dev/null | grep LISTENING | grep ":$1 " | awk '{print $5}' | sort -u)
  for pid in $pids; do
    [ -n "$pid" ] && [ "$pid" != "0" ] && taskkill //PID "$pid" //F >/dev/null 2>&1 || true
  done
}
free_port 5173; free_port 8080; free_port "$STUB_PORT"
sleep 2

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
  sleep 1
}
trap cleanup EXIT

wait_for() { # url, tries
  for _ in $(seq 1 "${2:-60}"); do
    [ -n "$(curl -s -m 2 "$1" || true)" ] && return 0
    sleep 2
  done
  echo "等待超时: $1" >&2
  return 1
}

echo "== 1/5 启动模型桩 =="
# 分片延迟调大，便于观察“逐字出现”
STUB_CHUNK_DELAY_MS=250 python "$ROOT/tools/llm-stub/stub.py" >/tmp/e2e-stub.log 2>&1 &
PIDS+=($!)
wait_for "http://localhost:$STUB_PORT/health" 15

echo "== 2/5 构建并启动后端 =="
(cd "$ROOT/backend/agent-server" && "$MVN" -B -q package -DskipTests) || { echo "后端构建失败" >&2; exit 1; }
nohup "$JAVA_BIN" -jar "$ROOT/backend/agent-server/target/agent-server-0.0.1-SNAPSHOT.jar" >/tmp/e2e-app.log 2>&1 &
PIDS+=($!)
wait_for "$API/api/health" 60

echo "== 3/5 准备 provider / model / agent（指向桩）=="
TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "登录失败" >&2; exit 1; }
AUTH="Authorization: Bearer $TOKEN"
CT="Content-Type: application/json"

RUN_ID=$(date +%s)
PROV=$(sed -e "s/__STUB_PORT__/$STUB_PORT/" -e "s/__RUN_ID__/$RUN_ID/" "$ROOT/scripts/e2e/provider.json" \
  | curl -s -X POST "$API/api/model-providers" -H "$AUTH" -H "$CT" --data-binary @-)
PID=$(echo "$PROV" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
MODEL=$(sed -e "s/__PROVIDER_ID__/$PID/" -e "s/__RUN_ID__/$RUN_ID/" "$ROOT/scripts/e2e/model.json" \
  | curl -s -X POST "$API/api/models" -H "$AUTH" -H "$CT" --data-binary @-)
MID=$(echo "$MODEL" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
[ -n "$PID" ] && [ -n "$MID" ] || { echo "provider/model 创建失败: $PROV $MODEL" >&2; exit 1; }

AGENT_NAME="E2E-Chat-Agent-$RUN_ID"
AGENT_RESP=$(sed -e "s/__MODEL_ID__/$MID/" -e "s/__AGENT_NAME__/$AGENT_NAME/g" "$ROOT/scripts/e2e/agent.json" \
  | curl -s -X POST "$API/api/agents" -H "$AUTH" -H "$CT" --data-binary @-)
echo "$AGENT_RESP" | grep -q '"code":0' || { echo "agent 创建失败: $AGENT_RESP" >&2; exit 1; }
export E2E_AGENT_NAME="$AGENT_NAME"
echo "   provider=$PID model=$MID agent=$AGENT_NAME"

echo "== 4/5 启动 Vite =="
(cd "$ROOT/frontend" && nohup npm run dev >/tmp/e2e-vite.log 2>&1 &)
sleep 1
wait_for "http://localhost:5173/" 40
# 确认 5173 就是我们的实例（strictPort 下若不是本实例会直接报错）
grep -q "5173" /tmp/e2e-vite.log || { echo "Vite 未监听 5173" >&2; tail -5 /tmp/e2e-vite.log >&2; exit 1; }

echo "== 5/5 Playwright 浏览器验收 =="
mkdir -p "$ROOT/docs/acceptance"
(cd "$ROOT/frontend" && npx playwright test)
RESULT=$?
echo "playwright exit=$RESULT"
ls -1 "$ROOT/docs/acceptance"/*.png 2>/dev/null || true
exit $RESULT
