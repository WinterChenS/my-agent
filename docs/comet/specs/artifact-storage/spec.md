# artifact-storage

## Purpose

用**一张统一的表**记录会话相关的「来源文件」与「产物」，并按内容类型分别落地到 MinIO 或数据库；在会话页右侧以侧边栏分区展示。

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

## 右侧栏（前端）

- 会话页右侧新增侧边栏（可折叠），**两个分区**：
  - **来源文件**：`role=SOURCE` 的条目；
  - **产物**：`role=ARTIFACT` 的条目。
- 每条显示：文件名、大小、类型图标、时间；点击可下载/预览（**文本类看正文；图片类型看图片**）。
- 空状态给出提示（如「对话中上传文件或让 Agent 生成文件后会出现在这里」）。
- 侧边栏与消息区/输入框同一视觉体系；不遮挡主内容（宽度固定，可折叠）。

## 与 Agent 的关系（D22，用户确认「自动全收」）

每次 run 结束后，对 Agent 的工作区做一次扫描，把**本次 run 新增或修改/大小变化**的文件登记为 `role=ARTIFACT`：

> 实测修正（2026-10-11）：HarnessAgent 的工作目录是 `.myagent-workspace/<owner>/`（owner 为调用方身份，本项目即 `userId`），**不是** `.myagent-workspace/<threadId>/`。所以快照/比对都按 `<workspaceRoot>/<userId>` 递归，排除 `agents/`、`default/`、`memory/`、`.skills-cache/`、`uploads/` 这些框架内部目录；本次 run 的变更归到当前会话。

- 判定方式：run 开始前记录工作区文件快照（路径 → mtime+size），run 结束后对比；新增或 mtime/size 变化者即为产物。
- **排除框架内部状态**（否则侧边栏会被框架文件淹没）：
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

## 图片：气泡内渲染与预览（Task 10.6）

同一张图可能出现在三个位置，但**只有一份数据源**（`artifact` 表）：

| 位置 | 内容 | 条件 |
|---|---|---|
| 气泡**内**（助手气泡正文下方） | `image/*` 且 ≤ `INLINE_IMAGE_MAX_BYTES` 的产物，渲染成图片本身 | 该产物 `run_id` = 这条助手消息的 `runId` |
| 气泡**下方**产物框 | 非图片产物（文件名 + 大小 + 文本类前几行） | 同上 |
| 右侧栏「产物 / 来源文件」 | 全部条目 | `canPreview(item)` 为真时给预览按钮 |

**判定只有一处（不得各写一份、不得按扩展名另立清单）**：
`isImageArtifact(item) = (item.mime ?? "").startsWith("image/")`；
`canPreview(item) = item.previewable || isImageArtifact(item)`。两者都放 `src/api/artifacts.ts`。

**取字节只有一条路**：产物下载接口要带 `Authorization`，所以 `<img src>` 不能直连 —— 统一走已有的
`fetchArtifactBlobUrl(id)`（fetch → blob URL），并由唯一的 hook `useArtifactBlobUrl(item)` 复用（含 `revokeObjectURL`）。
气泡内图片、产物缩略图、右侧抽屉、正文 Markdown 图片**全部复用它**。

**正文里的 Markdown 图片**（模型可能写 `![](xxx.png)`）：复用 react-markdown 已有的 `components` 覆盖加一个 `img`：

1. `data:` / `blob:` / `http(s):` → 原样渲染（外部图不由本平台代理）；
2. 否则按 **basename（大小写不敏感）** 在**本会话产物**里找同名且 `image/*` 的项 → 转 blob 后渲染；
3. 匹配不到 → 一行「图片未找到」提示（**不得静默留破图**）。

不新增 URL 协议、不改官方 `deliver_artifact` 的返回值：官方工具只回一句文字（不含 URL），模型无从得知
`/api/artifacts/{id}/download`，所以“按文件名匹配”是唯一可行且可测的约定。
**正文里的内联图（如 data URI）不反向登记为产物** —— 产物只由官方交付与工作区扫描两条既有路径产生。

**上限与失败**：`INLINE_IMAGE_MAX_BYTES`（单处常量，与后端 `myagent.minio.max-file-size-mb` 同量级）；
超限不在气泡内内联，给一个可点开右侧抽屉的占位；加载失败给一行提示 + 可下载。

**边界**：只内联**助手**气泡（用户气泡里自己发的图仍只显示附件名，本次不动）；非图片产物仍在气泡下方产物框。

**自己做数据（测试自给自足，不给桩加路由）**：验收用的图片由用例用 `page.setInputFiles(input, { name, mimeType: "image/png", buffer })`
**内联**提供一张真 PNG（字节常量放一处共享 helper）；让 Agent 产出图片产物则复用桩**已有**的环境变量开关
（`STUB_TOOL_NAME` / `STUB_TOOL_ARGS`，`phase10.spec.ts` 已用这套起专属端口的桩）。

## 验收标准

- 上传一个文件后，`artifact` 中出现 `role=SOURCE` 行；文本类 `storage=DB` 且 `content` 有值，二进制类 `storage=MINIO` 且对象存在于 bucket。
- 右侧栏「来源文件」能列出刚上传的文件并可下载。
- Agent 生成文件后，`artifact` 出现 `role=ARTIFACT` 行，右侧栏「产物」可列出并下载。
- **框架内部文件不得出现在产物区**：跑一轮对话后，`artifact(role=ARTIFACT)` 中不应包含 `memory/**`、`agents/**/sessions/**`、`.skills-cache/**`、`uploads/**` 等路径。
- 同一 run 未改动任何文件时，不产生新的产物行。
- 同内容重复上传不产生重复行。
- MinIO 不可用时上传返回明确业务错误，对话主链路仍可用。
- 非属主访问 `/api/artifacts?sessionId=` 返回 403。
- **只写文件（未显式交付）的一轮**，其产物 `run_id` 非空且与该轮 assistant 消息的 `aguiMeta.runId` 一致 ——
  于是它出现在对应气泡下方（此前扫描路径 `run_id` 为 null，只能出现在右侧栏）。
- 助手气泡**内**能直接看到本轮图片产物（真解码，非破图），点击打开右侧抽屉看大图。
- 右侧栏「产物」「来源文件」里**图片类型都有预览按钮**，点开是图片（而不是“不支持在线预览”）。
- 正文写 `![](本会话已有图片文件名)` 能在气泡内渲染；写不存在的文件名时给提示而不是破图。
- 超过 `INLINE_IMAGE_MAX_BYTES` 的图片不在气泡内内联，但可点开抽屉。
