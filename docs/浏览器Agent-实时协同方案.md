# 「页面里真的有一个浏览器」怎么实现（实时协同浏览方案）

> 你的需求：**页面上真的有一个浏览器 → agent 点击/输入我都能看见 → 我自己也能操作**。
> 结论：**能做，而且不是"截图回放"，是"服务端一个真浏览器 + 实时画面 + 双向输入"**。
> 下面全是实测数据（在生产容器里跑过，跑完已清理并重启回到镜像原状）。

---

## 一、先说清楚：为什么不能直接 `<iframe src="目标站">`

这是最容易被想到、但**行不通**的一条路：

| 障碍 | 说明 |
|---|---|
| `X-Frame-Options: DENY/SAMEORIGIN`、CSP `frame-ancestors` | GitHub、百度、淘宝、绝大多数站点都禁嵌。浏览器会直接白屏 |
| 跨域限制 | 即使嵌进来，父页面**读不到 DOM、拿不到元素坐标、注入不了点击、截不了图** |
| agent 无法操作 | 因为上面两条，agent 根本没有可操作的句柄 |
| 拿不到"你看到的画面" | 跨域下连 canvas 抓帧都被污染（tainted canvas） |

所以"页面里真有一个浏览器"只有一种可靠实现：**浏览器跑在服务端（真实 Chromium 进程），
画面以视频帧流回前端 `<canvas>`，用户的点击/键盘反向注入到这个进程**。
这就是 Browserbase / Steel / n.eko / Chrome Remote Desktop 的做法 —— **仍然是纯 B/S**。

关键点：**你看到的和 agent 操作的是同一个页面实例**（同一个 Chromium 进程、同一个 DOM、
同一套 cookie/网络），不是"两套"。所以不会出现"agent 看到的内容和你看到的不一样"。

---

## 二、实测数据（不是估算）

在一个 1280×800 的视口上，同一会话里同时验证了「agent 操作」「用户接管」「画面回流」：

| 指标 | 实测值 | 含义 |
|---|---|---|
| **点击 → 画面推回前端** | **31ms** | 你几乎立刻看到 agent 点击的效果（同机房/局域网） |
| agent 点击是否真生效 | 页面背景 `#e5e7eb → rgb(34,197,94)` | 真 DOM 事件，不是"假装点了" |
| **两路输入并存** | agent 用 Playwright 点、用户用 CDP 注入打字，**同一个 input 拿到 `"user typed via CDP"`** | 双人协作在技术上成立 |
| 用户点画布（坐标回传） | CDP 注入后页面同样响应（背景变绿） | 你可以直接接管点 |
| **帧率上限** | 连续动画时 **59.5 fps** | CDP screencast 能到 60fps，不是幻灯片 |
| 单帧大小 | 简单页 13.6KB／复杂页（百度首页）56KB（JPEG q60 @1280×800） | 带宽可控，见下 |
| 浏览器启动 | 66ms | 会话建立很快 |
| 打开真实站点 | baidu 662ms / example.com 1542ms | 与普通浏览器一致 |
| **3 个页面并发** | 内存 1.0→2.62GB、CPU **162%（2 核打满）** | 这台机器的硬上限 |

**带宽换算**：60fps × 56KB ≈ 3.4MB/s（太肥）。实际要限制：帧率 10–15fps + q40–60 + 只在画面变化时推
（screencast 本来就只在变化时推帧）→ 实际 **150KB/s – 700KB/s**，局域网/内网完全够。

---

## 三、"看得到"的三档方案（成本阶梯，可按需选）

| 档 | 做法 | 观感 | 服务器成本 | 工作量 |
|---|---|---|---|---|
| **L1 伪实时** | 每步动作后推一张新截图 + 在前端画光标轨迹/点击涟漪 | 2–5 秒一帧，"看得到它在动" | **0 增量**（复用现有截图能力） | 1 天 |
| **L2 真实时（推荐）** | CDP `Page.startScreencast` → 帧流 → `<canvas>`；输入反向注入 | **31ms 延迟、可 15–30fps**，可接管 | 常驻 1 个 Chromium（+150–300MB） | 2–4 天 |
| **L3 图传级别** | WebRTC（n.eko / xpra）或 noVNC + Xvfb | 60fps、**有声音**、可看视频 | 高（编码 CPU + 3 个额外服务） | 1–2 周 |

对你这个需求（"看得见 agent 点/输入，我也能操作"）：**L2 就够**，L3 只在"要放视频/要声音"时才需要。

---

## 四、L2 的具体设计（要改的地方）

```
┌──────────── 前端（产物预览区新增一个 tab：browser）────────────┐
│  <canvas>  画 JPEG 帧（尺寸 = 视口 × 缩放比）                    │
│  顶部工具条：地址栏 / 后退前进 / 刷新 / 「我接管」开关 / 缩放      │
│  overlay：agent 光标 + 点击涟漪 + "agent 正在输入：xxx" 提示条     │
└───────▲──────────────────────────────────────┬────────────────┘
        │ 画面帧（WS/SSE，15-30fps）             │ 用户输入（WS/REST）
┌───────┴──────────────────────────────────────▼────────────────┐
│ BrowserScreencastController  （帧广播 + 输入转发 + 控制权协调）   │
│ BrowserSessionRegistry       （每用户 1 个 context、TTL、闸门）  │
│ BrowserTools                 （open/click/type/scroll/read/shot）│
└───────┬───────────────────────────────────────────────────────┘
        │ CDP（同一个 Chrome 实例）
   ┌────▼─────┐
   │ Chromium │  真实进程：DOM/JS/Cookie/网络都在这里
   └──────────┘
```

**画面通道选型**：新增 `spring-boot-starter-websocket`（+1 个依赖，几十 KB）跑双向 WS；
如果想零依赖，用 SSE 推帧 + REST 回传输入也能跑（我们 `/agui/run` 的 SSE 已配好 nginx
`proxy_buffering off`），但 30fps 下 SSE 的背压不如 WS 好用。**建议 WS**。

**输入映射**：canvas 显示尺寸 ↔ 视口尺寸必须按 `rect` 比例换算（高 DPI 尤其注意），
然后把 `{type:'mousePressed', x, y}` 用 `Input.dispatchMouseEvent` 注入；键盘用
`Input.insertText`（中文/输入法）或 `Input.dispatchKeyEvent`（快捷键）。

**控制权（这是最容易吵的地方，必须定死）**：
- 默认 **agent 独占**：用户看到画面但不能点（画布 `pointer-events:none`）。
- 用户点「我接管」→ 服务端给该会话加锁：**agent 的下一步工具调用立即返回
  「用户正在操作，请等待」**（不是静默失败，模型能理解并停下）；用户点「交还」后恢复。
- 高危动作（提交表单、付款、发消息、下载）无论谁在操作都走**审批卡**（已有 `demo_confirm_card`）。
- 不做"两边同时乱点"——那是 bug 温床，不是功能。

**"点击/输入看得见"怎么实现**：
1. agent 的动作**用真实 CDP 事件注入**（不是改 DOM）→ 页面自己的 hover/按下/焦点效果都会出现，
   这是最自然的视觉反馈；
2. 前端再叠一层 **overlay**：收到动作事件时在对应坐标画一个光标 + 涟漪，并显示"正在输入 xxx"，
   解决"无头浏览器画面里没有系统鼠标指针"的问题；
3. 想更真可以往页面里注入一个假的鼠标 DOM 节点（截图/流里就有），但会污染页面，不推荐做默认。

**已知限制（说在前面）**：
- screencast **没有声音**，看不到视频（YouTube 会是一片静止画面）→ 要声音/视频才上 L3；
- 页面滚动的帧率取决于"画面变化量"，纯文字页面滚动时帧会稀疏，属正常；
- 无头浏览器可能被反爬识别（客户目标站点如果风控严，需要有效 UA/指纹策略，**不追求破解验证码**）；
- 每个会话 = 一个真进程，**必须**有 TTL（建议 10 分钟）与空闲回收（≥90 秒），否则内存会攒。

---

## 五、和现有系统的契合点（复用，不重造）

| 现有能力 | 怎么用 |
|---|---|
| **产物预览区 tab 机制** | 新增一种 tab 类型 `browser`，和 image/csv 并列，不用改布局 |
| **artifact 管道** | L1/L2 的每一步截图都作为 artifact 存档 → "回放 agent 做过什么" |
| **ReachUrlPolicy（SSRF 护栏）** | 浏览器的 `open` 必须过同一套：禁内网/云元数据/非 http/敏感端口 |
| **审批卡（demo_confirm_card）** | 高危动作人工确认 |
| **agent-reach 的 HOME 隔离思路** | 每用户一个 browser profile 目录，登录态互不可见 |
| **scheduled_task** | 后台跑（无人值守）时浏览器无画面，只出截图/PDF 产物 |
| **agent-reach 登录类渠道** | 装了 Chromium 后，小红书/Twitter 扫码登录正好用这个实时画面完成 |

---

## 六、风险（必须先有结论再动手）

1. **资源**：这台 8GB/2 核、已用 4.2GB，**3 个页面就把 CPU 打满**（实测 162%）。
   → L2 只能 **单并发**（最多 2）；要多人在线实时看，必须加内存到 16–32GB 或独立浏览器节点。
2. **SSRF**：浏览器比 `internet_read` 危险得多 —— 必须和"容器出站白名单"一起做。
3. **提示注入**：页面上的文字能操纵 agent 点按钮/填表单 → 高危动作强制审批。
4. **隐私/合规**：画面里可能有你的登录态与验证码 → 鉴权 + 不留档（或短期）+ 审计；
   自动化访问第三方站点可能违反其条款，默认只做"只读 + 用户指定站点"。
5. **别把它当"绕过反爬/验证码"的万能钥匙**：那是合规红线，且技术上必输。

---

## 七、如果要做，建议这样排

| 期 | 内容 | 工作量 | 服务器 | 产出 |
|---|---|---|---|---|
| **L1** | 产品预览区加"browser" tab，展示 agent 每步截图 + 光标轨迹 | 1 天 | 0 | 已能满足"看它干了啥" |
| **L2a** | 单个 Chromium 常驻 + CDP screencast + canvas 显示（只读画面，不可接管） | 2 天 | +250MB | **"真的有一个浏览器"** |
| **L2b** | 用户接管（控制权锁 + 输入注入 + 光标 overlay） | +1–2 天 | 同 | **"我也能操作"** |
| **L2c** | 多用户隔离 / 并发闸门 / 配额 / 审计 / 审批卡联动 | +2–3 天 | 需要加内存 | 可对外 |

> 一句话：**你要的"页面里真有一个浏览器、agent 点哪我看得到、我也能上手" = L2a + L2b，
> 技术已实测可行（31ms / 60fps / 双路输入），主要代价是每会话一个 Chromium 进程的内存，
> 以及必须先定好"控制权归谁"和"出站白名单"。**

---

## 十、落地记录（v1 已上线）

### 10.1 已实现的东西

| 需求 | 实现 |
|---|---|
| 面板上方两个图标按钮（文件 / 浏览器）切换 | `ArtifactPreviewPanel` 顶部加 `FileText` / `Globe` 分段按钮，两种视图互不覆盖 |
| 面板收起时，右上角一键打开/关闭 | `ChatPage` header 加「产物 / 浏览器」按钮（`PanelsTopLeft`），浏览器在跑时带绿点 |
| agent 打开浏览器 → 自动打开浏览器页面 | `ChatPage` 每 3 秒轻量轮询 `/api/browser/status`，`active && url 变化` 时自动切到浏览器视图并展开面板（**不打断**原有的"产物自动打开"逻辑：产物来了会切回「文件」） |
| agent 能操作 | 7 个工具：`browser_open / read / click / type / scroll / screenshot / close` |
| 用户也能操作 | 「我要操作」→ 点击/滚轮/键盘回传 `/api/browser/input`；此时服务端把 agent 的下一步操作挡住（控制权锁） |
| 铺满、无上下黑边 | canvas 内部像素 = 面板尺寸；同时 `Emulation.setDeviceMetricsOverride` 改**真实视口** → 页面 reflow（不是缩放截图） |
| 拖拽面板 = 像浏览器一样挤压 | `ResizeObserver`（防抖 250ms）→ `/api/browser/viewport` → 页面重新排版，新帧正好填满 |

### 10.2 踩过并修掉的 3 个坑（都在生产实测暴露）

1. **在 CDP 事件回调里同步等响应 → 接收线程自锁**：
   `Page.screencastFrame` 里同步发 `screencastFrameAck` 并等响应，把 JDK WebSocket 的接收线程堵死，
   表现为 `Page.navigate` / `Input.dispatchMouseEvent` **全部 30 秒超时**。
   → 新增 `CdpClient.fire()`（只发不等），事件回调里一律用它。
2. **会话还没初始化就被回收**：`acquire()` 里先 `sessions.put()` 再 `ensureStarted()`，
   cleanupIdle 看到"还没 cdp 的半初始化会话"就当死会话 close 掉 → `Page.navigate` 报「CDP 已关闭」。
   → 改成"启动成功后再放入 map"，并给刚启动的会话 20 秒免回收保护。
3. **拖一下就白板**：改 `canvas.width/height` 会清空画布，而 screencast 只在画面变化时推帧 →
   静态页面拖完就再也画不出来。
   → 前端缓存最后一帧、尺寸变化后立刻重绘；后端改视口后主动 `dispatchEvent(new Event('resize'))`
   触发一次推帧。

### 10.3 接口

```
GET  /api/browser/status      → {enabled, active, url, title, width, height, userControlled, allowUserInput}
GET  /api/browser/frames      → SSE，每条 {"image":"<base64 jpeg>","width":W,"height":H}
POST /api/browser/input       → {type:click|scroll|text|key, x,y,deltaY,text,key}
POST /api/browser/navigate    → {url}
POST /api/browser/viewport    → {width,height}   # 拖拽面板时同步真实视口
POST /api/browser/takeover    → {on:true|false}  # 控制权在用户/agent 之间切换
POST /api/browser/close
```
注：`frames` 用 SSE 而不是 WebSocket，是因为我们已经有 SSE 通道且 nginx 已配 `proxy_buffering off`；
`EventSource` 不能带 Authorization，所以前端用 fetch + ReadableStream 自己解析。

### 10.4 配置（`myagent.browser.*`）

```yaml
myagent.browser:
  enabled: true
  chrome-binary: ${MYAGENT_BROWSER_BIN:chrome-headless-shell}   # 镜像里指向 /opt/chrome/chrome-headless-shell
  viewport-width: 1280
  viewport-height: 800
  jpeg-quality: 60
  max-fps: 15                # 60fps×56KB≈3.4MB/s 太肥；15fps 足够"看着它在动"
  idle-timeout-seconds: 600  # 没人看也没人点 → 杀进程
  max-concurrent-sessions: 1 # 2 核机器：3 个页面就能打满 CPU
  allow-user-input: true
  profile-root: ""           # 空 = <workspace>/.browser-profiles/<userId>（每用户登录态隔离）
```

镜像侧：`scripts/build-artifacts.sh` 下载 `chrome-headless-shell`（Chrome for Testing，版本写死）
到 `deploy/artifacts/chrome/`，`Dockerfile.backend` 装运行时依赖库（libnss3/libatk/libgbm…）并 `COPY` 进去。

### 10.5 本地 → 线上验证（先本地跑通再发布）

本地（Windows，`MYAGENT_BROWSER_BIN` 指向 win64 的 chrome-headless-shell）：
```
mvn verify → 666 通过 / 0 失败
npm test  → 241 通过、lint 0 error、build 通过

scripts/verify-browser-ui.py（Playwright 真截图）
  agent 调 browser_open → 面板自动出现，example.com 铺满无黑边
  拖窄面板 → 真实视口 447x785 → 320x785（页面 reflow）
scripts/verify-browser-takeover.py
  点「我要操作」→ 点页面上的 Learn more → URL 变成 https://www.iana.org/help/example-domains ✅
```
线上（192.168.3.10:18080）同样脚本复跑：面板铺满、agent 不再自动关浏览器、接管 UI 正常。

### 10.6 还没做的（下一步）

- 前后退历史（现在是"重新导航到地址栏的值"）、多标签
- 视频/音频（screencast 只有画面；要声音要上 WebRTC）
- 高危动作确认（付款/提交/发消息前弹审批卡）
- 多用户并发（需加内存或独立浏览器节点）
