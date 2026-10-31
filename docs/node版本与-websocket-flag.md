# Node 版本要求 & `--experimental-websocket`

## 结论

本项目要求 **Node >= 20.10**（`.nvmrc` 固定 `24.16.0`，`frontend/package.json` 的 `engines` 也写了）。

`--experimental-websocket` 这个 flag 的兼容性（实测）：

| Node | 接受该 flag | 全局 `WebSocket` |
|---|---|---|
| 18.x | ❌ `bad option` | 需要 flag / 需 ws 包 |
| 20.10 ~ 20.x | ✅ | 需要 flag |
| **22.x / 24.x** | ✅（仍接受，只是可省略） | ✅ 原生支持 |

所以给审计脚本（`scripts/lines.mjs`、`capture.mjs` 里 `spawn(process.execPath, ["--experimental-websocket", ...])`）
两个选择：

1. **换 Node 到 22/24**（推荐）—— flag 仍被接受，脚本不用改；
2. **改脚本**（更彻底，不依赖版本）：

```js
const [major] = process.versions.node.split(".").map(Number);
const relaunchArgs = major >= 22
  ? [__filename, ...rest]                       // 22+ 原生有 WebSocket
  : ["--experimental-websocket", __filename, ...rest];
```

## 本机排查

```bash
bash scripts/check-node.sh
```

会列出 PATH 里每个 node 的版本、是否接受该 flag、是否有全局 WebSocket。

本机（2026-10-31）实测：

```
pi-node/current        v22.23.2   接受   全局 WebSocket 可用
Program Files/nodejs   v24.16.0   接受   全局 WebSocket 可用   ← nvm 当前默认
微信web开发者工具/node  v16.13.1   拒绝   （第三方工具自带，别用它跑脚本）
hermes/node            v22.23.1   接受   全局 WebSocket 可用
```

> 如果某台机器上出现 `v18.x`：先 `which -a node` 看是哪一个（常见来源：nvm 旧版本、
> pip 的 `nodejs-bin` 包、某个 IDE/工具自带的 node 目录），再决定升级谁：
> - nvm：`nvm install 22 && nvm use 22`
> - pip 的 `nodejs-bin`：`pip install -U nodejs-bin`（或直接 `pip uninstall nodejs-bin` 改用系统 node）
> - 工具自带：把它从 PATH 里排到后面，或直接改脚本那行
