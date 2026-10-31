#!/usr/bin/env bash
# 排查「node 版本 / --experimental-websocket 不受支持」这类问题。
#
# 背景：有些审计脚本（scripts/lines.mjs、capture.mjs 之类）会
#   spawn(process.execPath, ["--experimental-websocket", ...])
# 重启自身。这个 flag 在 Node 18 上不存在（bad option），在 20.10+ 才有；
# 而 Node 22/24 已经原生支持 WebSocket（flag 仍然被接受，只是可省略）。
#
# 用法：bash scripts/check-node.sh
set -u

echo "== PATH 里会被用到的 node =="
which -a node 2>/dev/null | while read -r p; do
  printf '  %-58s %s\n' "$p" "$(timeout 5 "$p" -v 2>/dev/null || echo '(无法执行)')"
done

echo
echo "== 每个 node 对 --experimental-websocket 的支持 =="
which -a node 2>/dev/null | while read -r p; do
  if timeout 5 "$p" --experimental-websocket -e "0" >/dev/null 2>&1; then
    flag="接受"
  else
    flag="拒绝（会 bad option）"
  fi
  if timeout 5 "$p" -e "process.exit(typeof WebSocket === 'function' ? 0 : 1)" >/dev/null 2>&1; then
    ws="全局 WebSocket 可用（不需要 flag）"
  else
    ws="需要 flag"
  fi
  printf '  %-58s %-18s %s\n' "$p" "$flag" "$ws"
done

echo
echo "== 其它常见来源 =="
command -v nvm >/dev/null 2>&1 && echo "  nvm: $(nvm version 2>/dev/null || echo '(已安装)')" || echo "  nvm: 未安装/不在 PATH"
[ -n "${NODE_OPTIONS:-}" ] && echo "  NODE_OPTIONS=$NODE_OPTIONS（注意里面可能带了不兼容的 flag）" || echo "  NODE_OPTIONS: 空"
python -m pip show nodejs-bin 2>/dev/null | head -3 || echo "  当前 python 环境没有 nodejs-bin"

echo
echo "== 建议 =="
echo "  * 要跑这类脚本，用 Node >= 20.10（推荐 22 或 24）：nvm install 22 && nvm use 22"
echo "  * 如果 node 来自 pip 的 nodejs-bin：pip install -U nodejs-bin（或 pip uninstall nodejs-bin 改用系统 node）"
echo "  * 也可以直接改脚本那行：Number(process.versions.node.split('.')[0]) >= 22 时不要带该 flag"
