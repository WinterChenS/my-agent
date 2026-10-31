# my-agent 浏览器助手（Chrome/Edge 扩展）

对应设计：`docs/superpowers/specs/2026-10-31-local-browser-extension-design.md`（阶段 A 闭环）。

它做的事：让 Agent 在**你本机已登录的浏览器标签页**里操作网页，复用登录态，
不导出 Cookie、不持续上传截图、不需要本地 Node/Python 服务。

## 安装（内测：加载已解压的扩展程序）

1. Chrome 打开 `chrome://extensions`（Edge 是 `edge://extensions`），打开右上角「开发者模式」。
2. 点「加载已解压的扩展程序」，选择本目录 `browser-extension/`。
3. 扩展会自动打开「连接 my-agent」页：
   - 填平台地址（例如 `http://192.168.3.10:18080`），点「保存并连接」；
   - 点「开始配对」，会打开平台配对页，用你的账号确认配对码；
   - 回到扩展，状态显示「已配对」。
4. 在**要操作的网页**上点击扩展图标 → 侧边面板 →「授权当前标签页」。
5. 授权后默认是「你在控制」，点「交还给 Agent」它才会开始操作；随时可以「暂停」。

## 目录

```
manifest.json           MV3 清单（权限：storage/activeTab/scripting/sidePanel/debugger/alarms/tabs）
src/background.js       Service worker：连接、命令执行、状态机
src/connection.js       WSS 长连接（首帧 auth、心跳、指数退避重连）
src/pairing.js          设备配对（device code + PKCE S256）
src/debugger.js         chrome.debugger 封装 + 页面内固定模板（不接受远程 JS）
src/scheduler.js        单标签页串行队列、requestId 去重、lease 校验
src/policy.js           URL 策略（公网 http/https，拒绝内网/本机/内部页）
src/sidepanel.js        侧边面板：授权、暂停、交还、撤销
src/onboarding.js       安装引导：平台地址、配对、权限说明
```

## 安全边界（重要）

- **单标签页授权**：一次只授权一个标签页；换站要重新授权。
- **跨站失效**：页面跳到别的 origin 时，扩展拒绝执行并上报 `ORIGIN_CHANGED`。
- **不执行远程代码**：页面内的取值/点击都用扩展自带的固定模板，后端只能传动作和**数据参数**。
- **不申请 cookies 权限**：登录态留在浏览器里，扩展既不读取也不上传。
- **随时可暂停**：侧边面板的「暂停」在本机立即生效并清空未开始命令；已提交的网页动作无法回滚。
- **调试提示**：使用 `debugger` 权限时 Chrome 会显示「正在调试此浏览器」；点「取消」就等于撤销控制。

## 打包（内测 zip）

```bash
python3 scripts/build-extension.py   # 产出 deploy/artifacts/my-agent-extension.zip
```
