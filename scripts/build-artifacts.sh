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

echo "==> 打包浏览器扩展（地址取自 MYAGENT_PUBLIC_BASE_URL）"
if [ -f "$ROOT/.env" ]; then
  set +u
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set -u
fi
if [ -n "${MYAGENT_PUBLIC_BASE_URL:-}" ]; then
  python "$ROOT/scripts/build-extension.py" --platform-url "$MYAGENT_PUBLIC_BASE_URL"
  mkdir -p "$ROOT/frontend/public/downloads"
  cp "$ARTIFACTS/my-agent-extension.zip" "$ROOT/frontend/public/downloads/"
else
  echo "    跳过：.env 里没有 MYAGENT_PUBLIC_BASE_URL（上线前务必配置）"
fi

echo "==> 构建前端 dist"
cd "$ROOT/frontend"
# 已经有 node_modules 就直接 build（本机 dev server 正在用它们，npm ci 会锁文件失败）
if [ ! -d node_modules ]; then
  npm ci --no-audit --no-fund
fi
npm run build
rm -rf "$ARTIFACTS/dist"
cp -r dist "$ARTIFACTS/dist"
echo "    -> $ARTIFACTS/dist ($(du -sh "$ARTIFACTS/dist" | cut -f1))"

# 浏览器二进制（chrome-headless-shell）：Agent 的"真实浏览器"。
# 版本写死保证可重复；已存在就跳过（120MB，别每次都下）。
CHROME_VERSION="${CHROME_VERSION:-156.0.8078.80}"
CHROME_ZIP="$ARTIFACTS/chrome.zip"
if [ ! -x "$ARTIFACTS/chrome/chrome-headless-shell" ]; then
  echo "==> 下载 chrome-headless-shell $CHROME_VERSION"
  if [ ! -f "$CHROME_ZIP" ]; then
    curl -fsSL -o "$CHROME_ZIP"       "https://storage.googleapis.com/chrome-for-testing-public/${CHROME_VERSION}/linux64/chrome-headless-shell-linux64.zip"
  fi
  rm -rf "$ARTIFACTS/chrome" && mkdir -p "$ARTIFACTS/chrome"
  unzip -q -o "$CHROME_ZIP" -d "$ARTIFACTS/chrome.tmp"
  cp -r "$ARTIFACTS/chrome.tmp/chrome-headless-shell-linux64/." "$ARTIFACTS/chrome/"
  rm -rf "$ARTIFACTS/chrome.tmp"
  echo "    -> $ARTIFACTS/chrome ($(du -sh "$ARTIFACTS/chrome" | cut -f1))"
else
  echo "==> chrome-headless-shell 已存在，跳过下载"
fi

# Node.js 运行时（Agent 技能脚本要用：dashi-ppt / officecli 之类都是 node 工具）。
# 为什么固化进镜像：容器每次重建都会丢掉 `pip install` 进来的 node，
# 用户实测"上次装好、这次又没了"。这里下载官方 linux-x64 二进制，构建期写进镜像。
NODE_VERSION="${NODE_VERSION:-24.19.0}"
NODE_TGZ="$ARTIFACTS/node-v${NODE_VERSION}-linux-x64.tar.gz"
if [ ! -x "$ARTIFACTS/node/bin/node" ]; then
  echo "==> 下载 Node.js $NODE_VERSION (linux-x64)"
  if [ ! -f "$NODE_TGZ" ]; then
    python - "$NODE_TGZ" "$NODE_VERSION" <<'PY'
import sys, urllib.request
target, version = sys.argv[1], sys.argv[2]
url = f"https://nodejs.org/dist/v{version}/node-v{version}-linux-x64.tar.gz"
print("    downloading", url)
urllib.request.urlretrieve(url, target)
PY
  fi
  rm -rf "$ARTIFACTS/node" "$ARTIFACTS/node.tmp"
  mkdir -p "$ARTIFACTS/node.tmp"
  tar -xzf "$NODE_TGZ" -C "$ARTIFACTS/node.tmp"
  mv "$ARTIFACTS/node.tmp/node-v${NODE_VERSION}-linux-x64" "$ARTIFACTS/node"
  rm -rf "$ARTIFACTS/node.tmp"
  echo "    -> $ARTIFACTS/node ($(du -sh "$ARTIFACTS/node" | cut -f1))"
else
  echo "==> Node.js 已存在，跳过下载"
fi

# npm / npx 包装脚本修正（必须做，且是幂等的）
# 官方 tarball 里 bin/npm 是符号链接；在 Windows 上解压成普通文件后，它的
#   require('../lib/cli.js')  会解析到 /opt/node/lib/cli.js —— 那个文件不存在，
#   于是 `npm -v` 直接 MODULE_NOT_FOUND 崩掉（用户实测的正是这个启动脚本 bug）。
# 这里统一重写成指向真实入口的 sh 包装，跨平台一致。
if [ -d "$ARTIFACTS/node/lib/node_modules/npm" ]; then
  printf '#!/bin/sh
exec /opt/node/bin/node /opt/node/lib/node_modules/npm/bin/npm-cli.js "$@"
' > "$ARTIFACTS/node/bin/npm"
  printf '#!/bin/sh
exec /opt/node/bin/node /opt/node/lib/node_modules/npm/bin/npx-cli.js "$@"
' > "$ARTIFACTS/node/bin/npx"
  chmod +x "$ARTIFACTS/node/bin/npm" "$ARTIFACTS/node/bin/npx"
  echo "    -> 已重写 npm/npx 包装脚本（指向 lib/node_modules/npm/bin/*-cli.js）"
fi

echo "==> 完成。接下来： docker compose up -d --build"
