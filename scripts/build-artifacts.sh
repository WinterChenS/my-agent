#!/usr/bin/env bash
# 产出 Docker 部署用的构建物（后端 jar + 前端 dist）。
#
# 为什么要有这一步：部署服务器连 Maven Central 极慢且会断（实测），不能在服务器上编译，
# 所以在本机/CI 构建好，再由 deploy/Dockerfile.* 只做运行时镜像。
#
# 用法：
#   ./scripts/build-artifacts.sh            # 跳测试（默认）
#   RUN_TESTS=1 ./scripts/build-artifacts.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACTS="$ROOT/deploy/artifacts"
mkdir -p "$ARTIFACTS"

echo "==> 构建后端 jar"
cd "$ROOT/backend/agent-server"
if [ "${RUN_TESTS:-0}" = "1" ]; then
  mvn -B clean package
else
  mvn -B clean package -DskipTests
fi
cp target/agent-server-*.jar "$ARTIFACTS/app.jar"
echo "    -> $ARTIFACTS/app.jar ($(du -h "$ARTIFACTS/app.jar" | cut -f1))"

echo "==> 构建前端 dist"
cd "$ROOT/frontend"
npm ci --no-audit --no-fund
npm run build
rm -rf "$ARTIFACTS/dist"
cp -r dist "$ARTIFACTS/dist"
echo "    -> $ARTIFACTS/dist ($(du -sh "$ARTIFACTS/dist" | cut -f1))"

echo "==> 完成。接下来： docker compose up -d --build"
