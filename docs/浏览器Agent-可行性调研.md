# 浏览器 Agent 自动化：可行性调研（在现有 B/S 架构里做「产物预览区里的浏览器」）

> 结论先说：**能实现，而且不需要改成 C/S**。"浏览器自动驾驶"不等于"必须装在用户电脑上"——
> 只要浏览器跑在服务端、画面用流送回来，就还是纯 B/S。
> 真正的限制不是架构，是**这台服务器的资源**（8GB 内存 / 2 核，已用 4.2GB）和**登录态归属**。

---

## 一、先澄清一件事：为什么你看到的方案多是"本地客户端"

| 你看到的形态 | 本质 | 为什么常见 |
|---|---|---|
| Claude for Chrome / Nanobrowser / browser-use Extension | **浏览器扩展**，扩展用 `chrome.debugger` 直接驱动用户自己那个标签页 | 零服务器成本、**天然复用用户已登录的账号**、不受反爬指纹影响（就是真人浏览器） |
| Claude Computer Use / 桌面 agent | 本地虚拟机/桌面控制 | 能操作任意桌面应用，不只是网页 |
| Browserbase / Steel / browserless.io | **云端浏览器**（服务端无头 Chromium + 画面流送） | 这才是 B/S 做法，但要付费云资源，且数据出境 |

所以「本地客户端」流行是因为**成本 + 登录态**，不是架构限制。云端浏览器（B/S）完全成立，就是我们自己这台机器要扛得住。

---

## 二、在我们的架构里，它应该长在哪

```
前端 产物预览区（ArtifactPreviewPanel 的 tab）
   ├─ 现有：image / markdown / csv / sheet / docx / pdf / html / text
   └─ 新增：browser tab
        ├─ 静止模式：展示 agent 产出的「整页截图 / PDF」——零新增基础设施
        └─ 实时模式：<canvas> 显示服务端推来的 JPEG 帧；用户可点、可滚、可打字（接管）

后端
   BrowserSessionRegistry（每用户一个 context、TTL 回收、并发闸门）
        ├─ 工具层：browser_open / browser_click / browser_type / browser_read / browser_screenshot…
        ├─ 画面层：CDP Page.startScreencast → JPEG 帧 → SSE 推给前端（复用现有 SSE 通道）
        └─ 输入层：前端点击坐标 → REST → CDP Input.dispatchMouseEvent（用户接管）
```

关键：**复用我们已经有的三样东西**，不另造轮子。
1. **产物管道**（`deliverArtifact` + `artifact` 表 + 预览面板）：截图/PDF 直接作为产物，0 改动就能看。
2. **SSE 通道**（`/agui/run` 已经跑通，nginx 也已配 `proxy_buffering off` + 长超时）→ 推帧不用引入 WebSocket。
3. **SSRF 护栏**（`ReachUrlPolicy`）：浏览器能访问任意 URL，比 `internet_read` 更危险，必须复用同一套内网/元数据/端口拦截。

---

## 三、实测数据（就在生产容器里真跑了一遍，跑完已清理）

| 指标 | 实测值 | 说明 |
|---|---|---|
| 安装体积 | Chromium headless shell **120MB** + 依赖（`--with-deps` 装的 libnss3 等） | 镜像会长约 400MB（含 python/系统依赖） |
| 浏览器启动 | **66ms**（headless shell 很快） | 比 agent-reach 的 CLI 冷启动还便宜 |
| 打开页面 | example.com 1542ms / baidu.com **662ms** | 与正常浏览器一致 |
| 截图 | **68–216ms**，PNG 75–85KB | 完全够"看结果" |
| 抓文本 | `page.inner_text("body")` 直接拿到 | 比爬 HTML 干净得多 |
| **实时画面（CDP screencast）** | 2 秒内 3 帧（有变化才推），平均 **56KB/帧**（JPEG q60 @1280×800） | ≈ 局域网 1MB/s 以内；帧率可调 |
| **用户接管输入** | `mouse.click` / `keyboard.type` 均可用 | 前后端联动没问题 |
| **并发 3 个页面** | 容器内存 **1.0GB → 2.62GB**，CPU **162%**（2 核打满），宿主可用内存只剩 2.2GB | **这是真正的瓶颈** |

中文渲染、GitHub/百度这类重页面截图都正常（见 `logs/browser_shot_baidu.png`）。

---

## 四、四种方案对比（针对我们这台机器打分）

| 方案 | 服务器成本 | 需要用户装东西 | 能用用户登录态 | 后台/定时任务可用 | 适配结论 |
|---|---|---|---|---|---|
| **A. 服务端无头浏览器**（Playwright + Chromium in container） | 高（每实例 150–400MB + CPU） | 否 | 否（要自己维护登录） | ✅ | **推荐做，但只做单并发/任务型** |
| **B. 浏览器扩展**（本地 Chrome + 桥接到后端） | 极低 | 是（装扩展） | ✅ 天生复用 | ❌ 用户关浏览器就没了 | 想做"登录态场景"时补 |
| **C. 混合**（服务端跑无人值守；登录/实时走用户本机） | 低–中 | 可选 | ✅ | ✅ 部分 | **最务实** |
| **D. 云浏览器（Browserbase 等）** | 钱包/数据出境 | 否 | 否 | ✅ | 不推荐（外网+付费+合规） |

**这台机器（8GB/2 核，已用 4.2GB）的现实上限**：
- 并发 **1 个浏览器**（最多 2，且不同时跑别的重活）；单会话建议 TTL ≤ 10 分钟，空闲 90 秒回收。
- 想做「3–5 人同时各自一个实时浏览器」：**必须加内存到 16–32GB，或单独一台浏览器节点**（届时按 sidecar 方式接过来，架构不变）。

---

## 五、落地路线（建议三期，先小后大）

### P0：静止截图版 —— **半天到 1 天，风险最低，先上这个**
- 容器装 Playwright + Chromium；加 4 个只读工具：`browser_open`（打开并返回标题+正文摘要）、`browser_screenshot`（整页/首屏截图，作为 **artifact** 交付）、`browser_extract`（选择器或全文）、`browser_pdf`。
- 产出物走现有 `deliverArtifact` → **产物预览区立刻就能看**，前端 0 改动。
- 用途立刻可交付：网页存档、竞品页面截图、把要闻列表抓成 markdown、报表转 PDF。
- 同时**顺手解锁 agent-reach 的登录类渠道**（它要 Chromium），但登录仍需人工扫码 → 留给 P1。

### P1：实时协同浏览 —— **2–3 天**
- 后端：`BrowserScreencastController`（SSE 推 JPEG 帧，`/api/browser/{id}/frames`）+ `POST /api/browser/{id}/input`（点击/滚动/输入/粘贴）+ `POST /api/browser/{id}/navigate`。
- 前端：`<canvas>` 画帧 + 工具条（地址栏、刷新、接管开关、缩放适配 DPI）；坐标按 canvas 显示尺寸 ↔ 视口尺寸换算（**高 DPI 必须按 rect 比例换算，不能按像素猜**）。
- 复用：SSE 已有 nginx 配置；`ReachUrlPolicy` 复用为域名白名单/黑名单。
- 价值：用户能"看着 agent 干活并随时接管"，也能用它完成小红书/Twitter 这类**人工扫码登录**。

### P2：多租户与治理 —— **3–5 天**
- 每用户独立 browser context + profile 目录（照抄 agent-reach 的 `HOME=<root>/<userId>` 隔离思路）；
- 并发闸门 + 配额 + 排队 + 超时回收 + 崩溃自愈（浏览器进程崩了要能重建）；
- 高风险动作（提交表单、付款、发私信、下载）走**审批卡**（已有 `demo_confirm_card` 机制）；
- 审计（谁、什么时候、访问了哪个域名、点了什么）+ 截图保留策略（敏感信息别长期留）；
- 域名白名单 + 禁内网 + 禁 `file://` + 禁下载到工作区外。

---

## 六、修改范围（文件级）

### 后端（新增）
```
agui/browser/BrowserProperties.java          # enabled / 并发 / TTL / 视口尺寸 / 帧率质量 / 域名黑白名单 / 是否允许接管
agui/browser/BrowserSessionRegistry.java     # 每用户 context 池：创建、复用、TTL 回收、并发闸门、崩溃重建
agui/browser/BrowserUrlPolicy.java           # 复用 ReachUrlPolicy 的思路（内网/元数据/端口/协议）
agui/browser/BrowserTools.java               # browser_open / screenshot / extract / click / type / scroll / pdf
agui/browser/BrowserScreencastController.java# SSE 推帧 + REST 输入（P1）
```
### 后端（修改）
```
agui/McpToolkitFactory.java   # 注册 browser 工具（和 internet_* 同一处）
agui/AgentFactory.java        # 系统提示加「浏览器使用规则」（同 internet 那段）
pom.xml                       # com.microsoft.playwright:playwright（官方 Java 绑定）
deploy/Dockerfile.backend     # 装 Chromium：PLAYWRIGHT_BROWSERS_PATH=/ms-playwright + 构建期 install chromium
resources/application.yml     # myagent.browser.*
```
### 前端（新增/修改）
```
api/browser.ts                                  # 会话开/关、输入、截图列表（P1）
components/chat/BrowserPreview.tsx              # canvas + 工具条（P1）
components/chat/ArtifactPreviewPanel.tsx        # 增加 browser tab 类型（P0 可先不加，截图当 image 看）
deploy/nginx.conf                               # SSE 已有；若改 WebSocket 要加 Upgrade 头（不建议）
```

### 注意：Java Playwright 与 Python Playwright 二选一
- **Java 绑定（推荐）**：进程内调用，和工具层同一套错误处理/审计；但浏览器进程和后端同容器同 CPU。
- **Python sidecar**：我们已经在用 Python（agent-reach），可以复用一个"浏览器服务"进程；隔离更好，但要自己定协议。
- **不要**用 `@playwright/mcp`（需 node），当前镜像没 node，加它等于多一层运行时。

---

## 七、风险与合规（先想清楚再动手）

| 风险 | 说明 | 缓解 |
|---|---|---|
| **资源打爆** | 3 个页面就把 2 核算力吃满、内存 +1.6GB；同机还有别的服务 | P0 单并发、TTL 回收、限频；要做多人先加机器 |
| **SSRF 横向** | 浏览器能访问内网/云元数据，比 `internet_read` 更危险 | 复用 `ReachUrlPolicy` + 网络层出站白名单（和上次说的一样，缺这层就是缺一层） |
| **登录态/凭据** | 用户登录态落在服务器 profile 里 | 每用户目录隔离 + 不落库 + 提供"退出登录/清除"入口；敏感站点默认不碰 |
| **提示注入** | 网页内容能操纵 agent 点按钮、填表单 | 高风险动作走审批卡；只读域与写域分级 |
| **反爬识别** | 无头浏览器会被识别（百度/GitHub 这次正常，但很多站会拦） | 有头模式 + 真实 UA + 限速；**不要**追求"绕过验证码"，那会踩合规红线 |
| **合规/ToS** | 自动化访问第三方站点可能违反其条款 | 默认只做"用户自己的目标站点 + 只读"，写操作要人确认 |
| **截图泄漏** | 截图里可能含 token/隐私 | 保留期 + 访问鉴权 + 不落公共目录 |

---

## 八、我的建议

1. **先做 P0（截图版）**：半天到一天，零前端改动、风险最低，能立刻把"agent 会开浏览器、把页面存下来给我看"这件事落地到产物预览区。
2. **P1 的实时画面是我们这套架构的"亮点"**，技术上完全可行（实测 CDP screencast 56KB/帧、可反向输入），但它**把服务器变成一个"给所有人开的浏览器"**，必须先解决并发与网络出站隔离，再对外开。
3. **不要一上来做实时 + 多用户**：这台 8GB/2 核机器扛不住 3 个页面并发（实测 CPU 162%）。要么限定单并发，要么先把内存加到 16–32GB。
4. 如果目标是"帮我操作我自己的账号"（下单、发帖、填表），**扩展方案（B）比服务端方案更合适**——那不是架构问题，是登录态归属问题。

> 一句话回答你的问题：**B/S 完全能做浏览器 agent（云端浏览器就是这个），我们缺的不是架构，是「并发资源」和「登录态归属」这两件事的决定。**
