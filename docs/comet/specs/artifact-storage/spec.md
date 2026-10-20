# artifact-storage

## Purpose

用**一张统一的表**记录会话相关的「来源文件」与「产物」，并按内容类型分别落地到 MinIO 或数据库；
在会话页**按类型渲染预览**（右侧内联预览面板 + tab），清单本身收进一个**弹窗**，不常驻占位置。

## 数据模型

`artifact`（统一表，`V5` 迁移创建）：

| 列 | 说明 |
|---|---|
| `id` | BIGSERIAL PK |
| `session_id` | 归属会话（FK chat_session） |
| `user_id` | 归属用户（冗余，便于权限与聚合） |
| `role` | `SOURCE`（用户上传的来源文件）/ `ARTIFACT`（Agent 产出） |
| `storage` | `MINIO` / `DB` |
| `file_name` | 原始文件名（如 `report.md`） |
| `mime` | MIME 类型 |
| `size_bytes` | 字节数 |
| `sha256` | 内容摘要（去重与校验） |
| `minio_bucket` / `minio_object_key` | `storage=MINIO` 时必填 |
| `content` | `storage=DB` 时存正文（TEXT） |
| `status` | `PENDING` / `READY` / `FAILED` |
| `source_message_id` | 关联的 chat_message（可选） |
| `run_id` | 产出它的那一轮 run id（Agent 交付、以及本轮工作区扫描登记时写入）→ 前端据此挂到**对应气泡**下方 |
| `created_at` / `updated_at` | 时间戳 |

索引：`(session_id, role, id)`、`(sha256)`。

## 存储路由（D18）

- **文本类**：`text/*`、`application/json`、`application/xml`、`application/yaml`、以及扩展名属 md/txt/json/csv/xml/yaml/yml/log 等 → `storage=DB`，正文写 `content`。
- **其它**：二进制、图片、压缩包、未知类型 → `storage=MINIO`，对象键形如 `artifacts/{userId}/{sessionId}/{sha256前8位}-{fileName}`。
- 两种情况都记录 `size_bytes` / `sha256` / `mime` / `file_name`；同一会话内 `sha256` 相同的重复上传只登记一条（返回既有记录）。

## MinIO 集成

- 客户端：Java `io.minio:minio`（环境变量：`MINIO_ENDPOINT`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`MINIO_BUCKET`（默认 `agent-platform`）、`MINIO_PUBLIC_ENDPOINT`）。
- 启动时确保 bucket 存在（不存在则创建）。
- 下载链接基于 `MINIO_PUBLIC_ENDPOINT` 生成（不依赖后端代理）。
- MinIO 不可用时：产物登记标记 `FAILED` 并给出明确错误，**不得影响对话主链路**。

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/artifacts?sessionId=` | 该会话的产物与来源文件（仅属主） |
| GET | `/api/artifacts/{id}/download` | 下载/跳转：`storage=MINIO` 返回签名直链，`storage=DB` 直接返回文本内容 |
| DELETE | `/api/artifacts/{id}` | 删除记录（及 MinIO 对象） |

权限：仅会话属主可读/删；`ADMIN` 不默认跨用户读取会话产物。

## 清单入口与弹窗（前端）

> 变更（2026-10-20，用户要求）：清单不再是**常驻**的一列侧边栏（它把聊天区挤得很窄）。

- 入口是**一个按钮**：`open-artifact-browser-panel`（预览面板**左上角**，用户要求的位置）
  与 `open-artifact-browser`（聊天页头部，面板没开时也能进）。
- 点击打开一个**贴着该按钮下方**弹出的浮层 `ArtifactBrowserPopover`（Radix Popover，`side=bottom`/`align=start`），
  **竖长条**形状：`w-80`（320px）、高 `24rem`（比宽高，列表本来就是竖着长的），超出滚动。两个分栏：
  - **来源文件**：`role=SOURCE` 的条目；
  - **产物**：`role=ARTIFACT` 的条目。
- **分栏只在有内容时出现**（用户要求）：某类一条都没有就整栏不渲染，不留空列表/空提示；
  两类都空时只给一句「这个会话还没有来源文件或产物」。
- 每条显示：文件名、大小、存储位置（对象存储/数据库）、失败原因；操作：预览 / 下载 / 删除。
- 点「预览」→ 关闭弹窗，在**右侧预览面板**里打开（弹窗只是“选东西的地方”，不是看内容的地方）。
- 非模态（不抢焦点、不遮罩）：弹窗开着也不影响继续在聊天里输入；点外部/`Esc` 关闭。

## 与 Agent 的关系（D22，用户确认「自动全收」）

每次 run 结束后，对 Agent 的工作区做一次扫描，把**本次 run 新增或修改/大小变化**的文件登记为 `role=ARTIFACT`：

> 实测修正（2026-10-11）：HarnessAgent 的工作目录是 `.myagent-workspace/<owner>/`（owner 为调用方身份，本项目即 `userId`），**不是** `.myagent-workspace/<threadId>/`。所以快照/比对都按 `<workspaceRoot>/<userId>` 递归，排除 `agents/`、`default/`、`memory/`、`.skills-cache/`、`uploads/` 这些框架内部目录；本次 run 的变更归到当前会话。

- 判定方式：run 开始前记录工作区文件快照（路径 → mtime+size），run 结束后对比；新增或 mtime/size 变化者即为产物。
- **排除框架内部状态**（否则产物清单会被框架文件淹没）：
  - `agents/**/sessions/**`（会话转录 jsonl）
  - `memory/**`（记忆文件）
  - `.skills-cache/**`（技能缓存）
  - `uploads/**`（用户上传的来源文件，已有 `role=SOURCE` 记录）
- 单个文件超过大小上限（默认 50MB）时跳过并记 `FAILED` 与原因。
- 登记失败不影响 run 结果；同一 `sha256` 已登记过则不重复插入。
- **归属（`run_id`）**：两条登记路径都要写本轮 `runId` —— 官方 `deliver_artifact` 交付时由平台写入；
  工作区扫描时同样写入（`AguiRunController.finish` → `ArtifactCaptureService.endRun` → `ArtifactService.store(..., runId)`）。
  前端 `ChatPage` 按 `artifact.runId === 消息.runId` 过滤，才把产物展示在“产出它的那一轮”气泡下方。
  去重规则（同 `sha256` 返回既有行）会让**先登记**的那条胜出：交付在 run **中**、扫描在 run **末**，
  所以交付那条（带 `runId`）优先，扫描命中去重不会把 `run_id` 抹掉。

## 产物预览：按类型渲染 + 统一右侧面板（Task 10.7）

### 一个渲染体，一个类型判定

| 文件 | 职责 |
|---|---|
| `src/lib/artifacts/previewKind.ts` | **唯一**的 MIME/扩展名 → 渲染类型判定：`image \| pdf \| html \| markdown \| text \| sheet \| docx \| none`，外加 `canPreviewArtifact()`。带扩展名兜底（历史数据有 `octet-stream`，如 xlsx/docx 走上传时未带 MIME 的情况）。 |
| `src/components/chat/ArtifactPreviewBody.tsx` | **唯一**的按类型渲染体（见下表）。三个入口全部复用它。 |
| `src/components/chat/ArtifactPreviewPanel.tsx` | 右侧**内联挤压**面板：tab 行 + 整体关闭 + 文件名/大小/下载 + 内容区。 |
| `src/components/chat/ArtifactBrowserDialog.tsx` | 「来源文件与产物」清单弹窗（入口见上节）。 |

| 类型 | 渲染方式 | 代价 / 范围 |
|---|---|---|
| `image` | 鉴权下载 → blob URL → `<img>` | — |
| `pdf` | 鉴权下载 → blob URL（**带 `application/pdf`**）→ iframe，交给浏览器内置阅读器 | 依赖浏览器；不引 pdf.js |
| `html` | `<iframe sandbox="allow-scripts" srcDoc>` | 见下节安全策略 |
| `markdown` | `react-markdown` + `remark-gfm`（复用已有依赖） | — |
| `text` | `<pre>`（正文来自 `item.content`） | — |
| `sheet`（xlsx/xls） | **动态** `import("xlsx")`（SheetJS 0.18.5）→ `sheet_to_json` 二维数组 → React 表格；多 sheet 出 tab | 只在打开表格预览时下载该 chunk（429 kB），不进首屏包；最多画前 200 行 |
| `docx` | **零依赖**自己解 zip（见下） | 只提取文字：**无版式、无表格结构、无图片** |
| `none` | 「不支持在线预览，可下载」 | 包含老式 `.doc`（OLE 二进制）——**只给下载** |

**判定/取字节都只有一处**：`previewKind()` / `canPreviewArtifact()` 供面板、气泡产物框、清单弹窗共用；
`fetchArtifactBytes(id)`（鉴权 fetch → `Uint8Array`）是唯一的取字节实现，`fetchArtifactBlobUrl(id, mime)` 建在它之上，
由 `useArtifactBlobUrl(item, { anyMime })`（图片/PDF）与 `useArtifactBytes(item)`（Excel/Word）两个 hook 复用，切换/卸载即 `revokeObjectURL`。

### 面板行为

- 位置：聊天区**右侧**、**内联挤压**聊天区（不是遮罩抽屉），高度**占满窗口**；
  内容区（`min-h-0 flex-1 overflow-auto`）占满面板剩余高度 —— **HTML/PDF/图片不再写死 `70vh`**
  （那样会在下方留白、长文档还被截断；用户实测指出），改为 `h-full` 撑满可用高度。
- **宽度可拖拽**（用户要求）：左边界是一条 `cursor-col-resize` 的分隔线（`preview-resize-handle`），
  向左拖宽、向右拖窄；限位逻辑是纯函数 `lib/chat/previewPanelWidth.ts`
  （默认 448px；下限 320px；上限 = min(896, 窗口宽 × 70%)）。双击分隔线回默认宽度。
  拖拽用 pointer capture，鼠标快了也不会“跑出”把手。宽度不持久化（刷新回默认）。
- 多产物 → 上方 tab（`preview-tab`），每个 tab 可单独关（`preview-tab-close`）；关掉当前 tab 时选中项交给相邻 tab。
- 关掉**最后一个** tab（或点整体关闭 `preview-panel-close`）→ 面板消失。
- **自动打开**：本会话出现新的可预览产物（`role=ARTIFACT`）时自动打开面板并显示它；同一个产物只抢一次焦点（按 id 记“已自动打开过”），用户手动关掉后不再自动弹。
- 开关**不持久化**（刷新回默认）；换会话清空所有 tab。
- 气泡内图片、产物框、清单弹窗三处点击都进同一个面板（不再有“窄栏弹窗 + 右侧抽屉”两套 UI）。

### 气泡下方的产物：小卡片，不内联正文

> 变更（2026-10-20，用户要求）：以前每条产物带一段内联正文预览（文本前 6 行）或缩略图，把产物区撑得很高；
> 现在改成**一个小长方形卡片**：

| 位置 | 内容 |
|---|---|
| 卡片左 | 类型图标（`image/*` → 图片图标，其余 → 文件图标） |
| 卡片中 | 两行：文件名（过长截断）+ 大小 |
| 卡片右 | **眼睛图标**（提示“点开能预览”；整张卡片都是一个 `<button>`，所以它只是装饰、不嵌套按钮） |

- 卡片固定宽 `w-56`、横排 `flex-wrap`（多了自动换行）；一个产物 ≈ 51px 高。
- **不再**内联正文、**不再**放缩略图；点卡片 → 右侧预览面板。

### HTML 沙箱策略（决策 2B，含残余风险）

`sandbox="allow-scripts"`，**不给** `allow-same-origin`：

- 能跑脚本 → 图表/交互式报告可用；
- 拿不到本站的 origin → 读不到 `localStorage`/cookie/本站 token，也改不了本站 DOM；
- **残余风险（用户已确认接受）**：脚本仍能**自行发起外部请求**（把内容外发）。所以只对**本会话产物**启用，
  且不把任何凭据注入 `srcDoc`。若以后要收紧，唯一办法是不给 `allow-scripts`（图表就没法跑）。

### Word（.docx）：零依赖抽文字（决策 1C）

用户明确**不引 `mammoth`**。`.docx` 本质是 zip，`src/lib/artifacts/docxText.ts` 自己解：

1. 从尾部找 EOCD → 读中央目录 → 找 `word/document.xml`（`listZipEntries` 单测覆盖）；
2. method 8 → `DecompressionStream("deflate-raw")`（读写**并发**进行，避免大文件互等）；method 0 → 直接用（stored，测试最好造）；
3. `DOMParser` 解析 XML，按 `<w:p>` 分段取 `<w:t>`，`<w:tab/>`→制表符、`<w:br/>`→换行，表格单元格里的段落同样能读到；
4. 失败（不是 zip / 没有 `document.xml` / 压缩方式不支持 / XML 坏）→ 界面上「无法解析这个 Word 文件，请下载后查看」。

**代价**：无版式、无表格结构、无图片；`.doc`（老 OLE 二进制）不在范围内 → 走 `none` 只给下载。

### 大文件保护

Excel / Word 超过 `INLINE_IMAGE_MAX_BYTES`（8MB）不解析，只提示下载（`preview-too-large`）；
表格最多渲染前 200 行（超出提示「完整内容请下载」）。

### 正文里的 Markdown 图片（不改）

同上节的既有约定（`data:`/`blob:`/`http(s)` 原样；否则按 basename 在本会话产物里找 `image/*`；找不到给提示）。

**正文里的 Markdown 图片**（模型可能写 `![](xxx.png)`）：复用 react-markdown 已有的 `components` 覆盖加一个 `img`：

1. `data:` / `blob:` / `http(s):` → 原样渲染（外部图不由本平台代理）；
2. 否则按 **basename（大小写不敏感）** 在**本会话产物**里找同名且 `image/*` 的项 → 转 blob 后渲染；
3. 匹配不到 → 一行「图片未找到」提示（**不得静默留破图**）。

不新增 URL 协议、不改官方 `deliver_artifact` 的返回值：官方工具只回一句文字（不含 URL），模型无从得知
`/api/artifacts/{id}/download`，所以“按文件名匹配”是唯一可行且可测的约定。
**正文里的内联图（如 data URI）不反向登记为产物** —— 产物只由官方交付与工作区扫描两条既有路径产生。

**上限与失败**：`INLINE_IMAGE_MAX_BYTES`（单处常量，与后端 `myagent.minio.max-file-size-mb` 同量级）；
超限不在气泡内内联，给一个可点开右侧抽屉的占位；加载失败给一行提示 + 可下载。

**边界**：只内联**助手**气泡（用户气泡里自己发的图仍只显示附件名，本次不动）；非图片产物仍在气泡下方，
以**小卡片**呈现（见下节）。

**自己做数据（测试自给自足，不给桩加路由）**：验收用的图片由用例用 `page.setInputFiles(input, { name, mimeType: "image/png", buffer })`
**内联**提供一张真 PNG（字节常量放一处共享 helper）；让 Agent 产出图片产物则复用桩**已有**的环境变量开关
（`STUB_TOOL_NAME` / `STUB_TOOL_ARGS`，`phase10.spec.ts` 已用这套起专属端口的桩）。

## 验收标准

- 上传一个文件后，`artifact` 中出现 `role=SOURCE` 行；文本类 `storage=DB` 且 `content` 有值，二进制类 `storage=MINIO` 且对象存在于 bucket。
- 清单弹窗的「来源文件」能列出刚上传的文件并可下载。
- Agent 生成文件后，`artifact` 出现 `role=ARTIFACT` 行，清单弹窗「产物」可列出并下载。
- **框架内部文件不得出现在产物区**：跑一轮对话后，`artifact(role=ARTIFACT)` 中不应包含 `memory/**`、`agents/**/sessions/**`、`.skills-cache/**`、`uploads/**` 等路径。
- 同一 run 未改动任何文件时，不产生新的产物行。
- 同内容重复上传不产生重复行。
- MinIO 不可用时上传返回明确业务错误，对话主链路仍可用。
- 非属主访问 `/api/artifacts?sessionId=` 返回 403。
- **只写文件（未显式交付）的一轮**，其产物 `run_id` 非空且与该轮 assistant 消息的 `aguiMeta.runId` 一致 ——
  于是它出现在对应气泡下方（此前扫描路径 `run_id` 为 null，只能出现在产物清单里）。
- 助手气泡**内**能直接看到本轮图片产物（真解码，非破图），点击在右侧预览面板看大图。
- 清单弹窗里 **图片 / PDF / Excel / Word 都有预览按钮**（`canPreviewArtifact`），zip 等没有。
- 正文写 `![](本会话已有图片文件名)` 能在气泡内渲染；写不存在的文件名时给提示而不是破图。
- 超过 `INLINE_IMAGE_MAX_BYTES` 的图片不在气泡内内联，但可点开预览面板。
- **按类型渲染**：html 走沙箱 iframe（脚本可跑、读不到本站存储）、pdf 走内置阅读器、markdown 渲染成标题/表格、
  纯文本走 `<pre>`、xlsx 出表格且多 sheet 可切、docx 能抽到文字。
- **面板**：多产物出多个 tab、逐个可关、关完最后一个面板消失；出现可预览产物时**自动打开**；
  面板是内联挤压（在聊天区右侧），不是遮罩抽屉；**左边界可拖拽调整宽度**（双击回默认 448px）。
- **清单**：只在对应类型有内容时才出现该分栏；点预览后弹窗自动关闭、右侧面板打开；
  弹窗贴着触发按钮**下方**弹出，且**高 > 宽**（竖长条）。
