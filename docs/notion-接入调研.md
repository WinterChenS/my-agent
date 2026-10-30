# 接入 Notion 调研（2026-10-29）

结论先说：**推荐先做「原生连接器」（Notion REST API + 集成 token）**，
官方推荐的 Remote MCP 需要 OAuth 2.1（含动态注册 + PKCE），我们目前的 MCP 客户端只支持静态 header，
要接得先补一套通用 OAuth 支持；本地 MCP server 官方已停止维护，且镜像里没有 Node。

---

## 1. 我们已有的底座（决定了三条路线各自要补什么）

| 能力 | 现状 | 对 Notion 的影响 |
|---|---|---|
| 连接器框架 | 类型描述（字段/能力/控件）→ Provider 产工具 → 按用户解析凭据 | 原生连接器**只加一个类型 + 一个 Provider**，前端零改动（表单、tag 白名单、启停、审计、聊天页入口全部复用） |
| MCP 客户端 | STDIO / SSE / STREAMABLE_HTTP，支持**静态 headers** | 能接 token 型 MCP；**不支持 OAuth 授权码流程** |
| 后端镜像 | JRE21 + Python3，**没有 Node** | STDIO 跑 `npx @notionhq/notion-mcp-server` 需要先加 Node（约 +100MB 镜像） |
| 用户隔离 | 连接器归属用户、工具按 `RuntimeContext.userId` 解析 | 每人用自己的 Notion token，天然不串号 |
| 治理 | 写操作 `isReadOnly=false` → 审批卡；白名单/限流/审计 | 可直接复用到 Notion 写操作（新建页面、追加内容） |

---

## 2. 实测到的环境事实

| 项 | 结果 |
|---|---|
| 服务器/容器 → `api.notion.com:443` | **可达**（TLS OK） |
| 服务器/容器 → `mcp.notion.com:443` | **可达** |
| 容器内 `node` | **没有**（`NO_NODE`） |
| Notion API 端点 | `/v1/users/me`、`/v1/search`、`/v1/pages/{id}`、`/v1/blocks/{id}/children`、`/v1/data_sources/{id}/query`、`/v1/databases/{id}/query` 均存在（401/400，非 404） |
| 鉴权 | `Authorization: Bearer <token>` + `Notion-Version: 2025-09-03`；token 错时返回 `{object:error,status:401,code:"unauthorized",message:"API token is invalid."}`，`request_id` 可用于排障 |
| `mcp.notion.com/mcp` | `401` + `WWW-Authenticate: Bearer realm="OAuth", resource_metadata=…`；元数据显示 `registration_endpoint`、`code_challenge_methods_supported:[S256]` → **完整 OAuth 2.1（DCR + PKCE）** |
| `developers.notion.com` | 从这台机器 **连不上**（DNS 有解析，连接失败）；因此以上都以 API 实测为准 |
| 官方 MCP server npm 包 | `@notionhq/notion-mcp-server@2.6.0`，README 明确写着：**不再积极维护**，建议改用 Remote Notion MCP |
| Notion API 2025-09-03 的变化 | 数据库查询走 **data source**（`query-data-source` 取代 `post-database-query`），旧 `/v1/databases/{id}/query` 仍在 |

---

## 3. 三条路线对比

### 路线 A：原生连接器（推荐先做）

用户到 Notion「设置 → 集成」建一个 **内部集成（internal integration）**，拿到 `secret_…` token，
再把要用的页面/数据库在 Notion 里「添加连接」给这个集成；然后在平台「能力 → 连接器」里粘 token。

| 优点 | 缺点 |
|---|---|
| 和邮件连接器同一套模型：按用户隔离、启停、白名单、审计、聊天页入口 | 需要自己实现 Notion 工具（约 6 个） |
| 不需要 Node、不需要 OAuth、不需要公网回调 | token 权限范围由"把哪些页面分享给集成"决定，用户要理解这一步 |
| 响应可裁剪（只回 markdown 正文），**省 token** | Notion block 结构复杂，markdown 双向转换要处理常见块 |
| 可做语义化的工具名与提示词（模型更容易用对） | 需要处理速率限制（约 3 req/s） |

### 路线 B：本地 MCP server（`@notionhq/notion-mcp-server`）

- 需要镜像加 Node（+100MB），STDIO 启动，`OPENAPI_MCP_HEADERS` 传 token；
- 工具是 Notion API 的机械映射（约 20+ 个），**响应大、耗 token**；
- 官方 README 已标注"不再维护 / 可能 sunset"。
- 结论：**不推荐**，但要接其它 token 型 MCP 时，给镜像加 Node 仍然是个独立选项。

### 路线 C：Remote Notion MCP（官方推荐）

- `https://mcp.notion.com/mcp`，streamable-http + OAuth 2.1：`/register`（DCR）→ `/authorize`（PKCE S256）→ `/token`（含 refresh）；
- 优势：Notion 官方维护、语义搜索、Markdown 读写、自动遵守用户 Notion 权限、**不用手动给页面加连接**；
- 代价：要给 MCP 客户端补 **通用 OAuth 支持**（回调地址、token 存储与刷新、DCR 注册、PKCE），
  而我们现在的 MCP 配置只有"静态 headers"，且平台是内网 http 部署（回调地址还得处理非 https 的情况）；
- 结论：**好，但不是一个"加个类型"就能完成的事**；建议放在 A 之后，作为"通用 OAuth MCP"能力来做。

---

## 4. 推荐方案 A 的具体设计

### 4.1 类型描述（新增一条 `NOTION`）

| 字段 | 控件 | 说明 |
|---|---|---|
| `token` | PASSWORD | Notion 内部集成 token（`secret_…` / `ntn_…`），只存密文 |
| `workspaceName` | TEXT | 备注用（可选） |
| `defaultPageId` | TEXT | 默认写入页（可选）：新建页面时 Append 到它下面 |
| `maxBlocksPerPage` | NUMBER | 单页最多返回/写入多少块（默认 200，防超大页面灌爆上下文） |
| `allowWrite` | SELECT | 允许写：是 / 否（只能读） |
| `allowedDatabases` | TAGS | 允许查询/写入的 data source id 白名单（空=不限） |
| `allowedPageIds` | TAGS | 允许读写的页面 id 白名单（空=不限） |

能力标签：`READ / SEARCH / WRITE`。

### 4.2 工具（6 个）

| 工具 | 读写 | 说明 |
|---|---|---|
| `notion_search` | 只读 | `/v1/search`：按标题/关键词找页面与 data source，可选 `type=page|data_source`、`limit`。**注意：Notion 搜索不搜正文**，只搜标题（要在提示词里写清，否则模型会以为能全文检索） |
| `notion_get_page` | 只读 | 页面元信息 + 正文：`/v1/pages/{id}` + `/v1/blocks/{id}/children`（递归 2 层，转 Markdown，超过 `maxBlocksPerPage` 截断并提示） |
| `notion_create_page` | **写** | `/v1/pages`：在指定页面/数据源下新建，正文用 **Markdown 入参**，服务端转 block 数组 |
| `notion_append_blocks` | **写** | `/v1/blocks/{id}/children`：往页面末尾追加 Markdown 片段 |
| `notion_query_data_source` | 只读 | `/v1/data_sources/{id}/query`：过滤/排序/分页，返回**表格化的属性**（不是原始 JSON，省 token） |
| `notion_list_data_sources` | 只读 | `/v1/search` 过滤 `data_source`，给出 id/标题/属性结构，让模型知道能查哪些库 |

（“更新页面属性/改状态”这类放到 P1：`notion_update_page_properties`。）

### 4.3 安全与治理（复用现有机制）

- token 走 `CryptoService` 加密，回显 `***`，编辑留空=不改；
- 写操作 `isReadOnly=false` → `REQUIRE_APPROVAL` 的 Agent 自动弹审批卡；
- `allowWrite=否` 时写工具直接返回「该连接器关闭了写入」；
- `allowedPageIds / allowedDatabases` 白名单：**只允许碰用户明确列出的页面/库**（空=不限，语义与邮件白名单一致，前端 tag 输入）；
- 写操作全部落 `connector_action_log`（谁、哪个页面、动作、成功/失败）；
- **限流**：Notion 平均 3 req/s，客户端侧串行 + 最小间隔 350ms + 429 重试（读 `Retry-After`）；
- 错误映射：`unauthorized`（token 失效）→「token 无效，请重新生成」；
  `object_not_found` →「这个页面/库没给集成授权：在 Notion 页面右上角『…』→ 添加连接 → 选你的集成」；
  `validation_error` → 原样带 message（多半是 block 结构不对）。

### 4.4 关键实现点

1. **Markdown ↔ Notion block**：只做常用子集（段落、标题 1-3、无序/有序列表、待办、引用、代码块、分割线、加粗/斜体/行内代码/链接）。不做表格与嵌套列（遇到就降级成纯文本块并注明）。
2. **页面 id 解析**：用户常粘完整链接 `https://www.notion.so/xxx-Workspace-2f1b…?pvs=4`，需要从中提取 32 位 id 并加连字符。
3. **递归深度**：读页面时 `has_children` 才会继续拉；默认深度 2、块数上限 `maxBlocksPerPage`，避免一次读 5000 块把上下文打爆。
4. **data source 与 database**：2025-09-03 之后查询走 data source；工具里两个都支持（先试 data source，参数不对再回退 database），并在提示词里告诉模型"数据库要传 data_source_id"。
5. 复用 `AbstractMailTool` 那套"停用/未绑定/白名单"提示语，抽成 `AbstractConnectorTool` 基类（邮件也顺手迁移过来），后续加连接器更快。

### 4.5 工作量估算

| 项 | 估时 |
|---|---|
| 类型描述 + Provider 骨架 + 配置/校验 | 0.5 天 |
| Notion 客户端（限流、错误映射、id 解析） | 0.5 天 |
| 6 个工具 + Markdown 双向转换 | 1 天 |
| 单测（block 转换、id 解析、白名单、错误映射、限流退避）+ MockWebServer/GreenMail 式集成测试（用本地 stub 模拟 Notion API） | 0.5~1 天 |
| 前端：**零改动**（复用动态表单，白名单是 tag 输入） | 0 |
| 真机联调（需要你的 token + 一个测试页面） | 0.5 天 |

合计约 **3 天**（不含 OAuth 路线）。

---

## 5. 验收清单（接完后怎么算通过）

1. 建连接器 → 「测试连接」显示工作区名与可访问页面数（`/v1/users/me` + `/v1/search`）；
2. 对话：「在 Notion 里搜一下『周报』相关页面」→ `notion_search` → 列出标题与链接；
3. 「把『XX』这个页面读出来，按 markdown 给我」→ `notion_get_page` → 结构正确、块数截断有提示；
4. 「在『项目记录』页面下新建一页，标题…正文…」→ `notion_create_page` → **弹审批卡**（Agent 是 REQUIRE_APPROVAL 时）→ 批准后 Notion 里能看到；
5. 「查一下任务库里面状态=进行中的前 5 条」→ `notion_query_data_source` → 表格输出；
6. 白名单生效：把页面 id 加进白名单后，访问别的页面被拒并给出可操作提示；
7. 停用连接器 → 下一轮对话明确说"已停用，怎么开"（不是"我没这个功能"）；
8. 审计里能看到每次写操作。

---

## 6. 风险与注意点

| 风险 | 说明 / 对策 |
|---|---|
| **集成必须被"添加连接"到页面** | 这是 Notion 的权限模型，不是 bug。错误映射里要把这句话写清，否则用户会以为 token 错 |
| 搜索不覆盖正文 | `/v1/search` 只搜标题（以及部分属性）。提示词里明确写，必要时让模型先读页面再判断 |
| 响应体大 | 只回 Markdown/表格，不回原始 JSON；块数上限；超长截断并提示 |
| 速率限制 3 req/s | 客户端串行 + 最小间隔 + 429 退避；批量操作时给模型提示"会较慢" |
| token 权限过大 | `allowWrite` 默认允许但可关；页面/库白名单；写操作审批 + 审计 |
| API 版本演进 | 版本头固定 `2025-09-03`；升级时只改客户端一处；工具层对 data source/database 两种形态都兼容 |
| 镜像无 Node | 走路线 B 才需要；A/C 都不需要 |

---

## 7. 需要你确认的

1. **走哪条路线**：A 原生连接器（约 3 天，推荐）/ C Remote MCP（要先做通用 OAuth，估计 1.5~2 天）+ 后续 / B 本地 MCP（要先加 Node，官方已弃维护，不推荐）。
2. **典型用法**：主要是「读页面/查数据库」（只读），还是也要「AI 往 Notion 写东西」？这决定白名单与审批的默认值。
3. **是否需要多人各自授权**：如果每个用户都用自己的 Notion token（连接器按用户隔离，天然支持），还是只给管理员配一个共享 Notion？
4. 有个 **token + 一个测试页面/数据库** 的话，我可以直接做端到端联调（只读先跑通，再开写）。

---

## 8. 补充调研：能不能「跳转授权」？（2026-10-29 追问）

**能。** Notion 现在有三种凭据模型，其中「公开连接（public connection）」就是标准的 **OAuth 2.0 跳转授权**：

| 模型 | 用户怎么授权 | 页面权限怎么给 | 是否适合"每人用自己的账号" |
|---|---|---|---|
| 内部连接 internal connection（机器人身份） | 粘 token | **手动**在页面「…」→ 添加连接 | 一般（团队共享用） |
| **个人访问令牌 PAT** | 粘 token | **直接用创建者本人的权限，无需共享页面** | ✅ 最省事 |
| **公开连接 public connection（OAuth）** | **跳转授权**（点「授权」→ 选页面 → 回调） | 授权时用**页面选择器**勾选 | ✅ 正规做法 |

### 8.1 OAuth 流程（已实测 + 文档确认）

1. 跳转：`https://api.notion.com/v1/oauth/authorize?owner=user&client_id=…&redirect_uri=…&response_type=code&state=…`
   - 实测返回 **302 → `app.notion.com/install-integration?...`**，`redirect_uri` 原样透传（我用 `http://192.168.3.10:18080/cb` 测过）；
   - 用户可点 **Cancel** → 回跳 `redirect_uri?error=access_denied&state=…`；
   - 用户点「选择页面」会打开**页面选择器**（只列该用户有完整权限的页面/数据库）。
2. 换取 token：`POST https://api.notion.com/v1/oauth/token`
   - `Basic base64(client_id:client_secret)` + JSON body `{grant_type:"authorization_code", code, redirect_uri}`；
   - 返回 `access_token / refresh_token / bot_id / workspace_id / workspace_name / owner / duplicated_template_id`；
   - 错凭据实测返回 `{"error":"invalid_client","request_id":…}`。
3. 刷新：存在 `POST /v1/oauth/token`（`grant_type=refresh_token`）→ 说明 **access token 会过期，必须实现刷新**，并把 `refresh_token`、过期时间一起加密存库。
4. 能力（capabilities）要在 Developer portal 勾：**Read content / Update content / Insert content**（我们要读+写，三个都要）。

### 8.2 一个必须先确认的前置条件：redirect URI 是否允许 http

- 官方文档所有示例都是 `https://…/callback`，**没有明说必须 https**，但 Developer portal 注册时是否接受 `http://192.168.3.10:18080/...` **未验证**（需要真的去 portal 填一次才知道）。
- 我们的现状是**内网 http** 部署。三条应对：
  1. 给平台加一层 https（Caddy/nginx + 内网自签或内部 CA 证书）——最正规，OAuth 也最稳；
  2. 若 portal 接受 http，直接可用（我实测 authorize 端点不拦 http）；
  3. 先只上 PAT 模式，等 https 就绪再开 OAuth。

### 8.3 版本与其它事实

- 当前 `Notion-Version` 文档示例是 **`2026-03-11`**（`2025-09-03` 引入 data source 抽象，`/v1/data_sources/{id}/query` 是数据库查询的新入口）。
- 读写能力对应：`Read content` / `Update content`（改页面属性、标题、归档）/ `Insert content`（新建页面、追加块）。
- PAT 受 workspace 的「PAT 创建策略」约束（管理员可查看/撤销），若公司 workspace 禁用了 PAT API 能力，就得走 OAuth。

### 8.4 结论与建议的落地顺序

按你的答复（要写、用户隔离）→ 分两段做，先能跑，再省事：

**P0：NOTION 连接器（token 模式：PAT 或内部连接 token）— 约 3 天**
- 字段：`token`(密码) / `workspaceName` / `defaultPageId` / `maxBlocksPerPage` / `allowWrite`(下拉) / `allowedPageIds`、`allowedDataSources`（tag 白名单）
- 工具（8 个）：`notion_search`、`notion_get_page`、`notion_list_data_sources`、`notion_query_data_source`、
  `notion_create_page`、`notion_append_markdown`、`notion_update_page`（属性/标题/归档）、`notion_get_comments`(P2)
- 写操作 `isReadOnly=false` → 自动进审批卡；`allowWrite=否` 直接拒；白名单外拒；审计落库
- 用户侧：Notion → Developer portal 建 PAT（勾 Notion API 能力）→ 粘到平台。**不需要跳转、不需要给页面加连接**

**P1：加「跳转授权」（OAuth）— 约 1.5 天**
- 字段加 `authMode`(TOKEN / OAUTH)；OAUTH 时表单显示「授权 Notion」按钮
- 后端：`GET /api/connectors/notion/authorize?connectorId=…` 生成带签名 state 的 authorize URL；
  `GET /api/connectors/notion/callback` 收 code → 换 token → 加密存 `access_token/refresh_token/expires_at/workspace`；
  之后每次调用前检查过期 → 自动 refresh；401 时刷新并重试一次
- 平台配置：`myagent.connector.notion.client-id/client-secret`（管理员配一次，不是用户填）
- 前置：Developer portal 注册 public connection + redirect URI 可访问（见 8.2）

**P2（可选）**：评论、文件上传、页面移动、模板复制；以及把 Notion 做成"入站通知"（页面变更 webhook）。

---

## 9. P0 实施记录（已完成，2026-10-30）

按「走 A + 要写入 + 用户隔离 + 先 P0」落地：**NOTION 连接器**（token 模式）已上线。

### 9.1 落地清单

| 层 | 内容 |
|---|---|
| 迁移 | `V21__connector_notion.sql`：`connector.type` 允许 `NOTION` |
| 类型描述 | `ConnectorTypeDescriptor.Capability.WRITE` + 注册表新增 `NOTION`（token / workspaceName / defaultPageId / maxBlocksPerPage / allowWrite / allowedPageIds / allowedDataSources；白名单是 **tag 输入**） |
| 客户端 | `NotionClient`：JDK HttpClient、`Notion-Version: 2026-03-11`、**350ms 最小间隔 + 429 退避**、错误人话化 |
| 转换 | `NotionMarkdown`：Markdown ↔ block 子集（标题/段落/列表/待办/引用/代码/分割线 + 行内粗斜体/代码/链接） |
| 工具 | `NotionTools` 7 个：`notion_search`、`notion_get_page`、`notion_list_data_sources`、`notion_query_data_source`（表格输出）、`notion_create_page`、`notion_append_markdown`、`notion_update_page` |
| 复用 | 「用哪个连接器」的解析抽成 `ConnectorToolResolver`（邮件与 Notion 共用一套提示语：未绑定 / 多个 / 已停用） |
| 校验 | 保存时 tag 白名单必须是邮箱/`@域名`（邮件）或 **Notion id**（Notion 会自动从链接里提取） |
| 前端 | **零改动**（动态表单 + tag 白名单 + 聊天页连接器入口全部复用） |

### 9.2 实测（真实 token，本机）

| 场景 | 结果 |
|---|---|
| 测试连接 | ✅ `令牌有效：my-agent · 集成令牌（机器人身份，需要把页面「添加连接」给它）`，并能搜到 10 项 |
| `notion_search` | ✅ 返回真实页面标题 + id + 编辑时间 |
| `notion_get_page` | ✅ 正文按 Markdown 返回，子页面引用也能识别 |
| `notion_create_page` | ✅ 新建页面（H1/段落/列表/待办都正确落库） |
| `notion_append_markdown` | ✅ 追加块成功 |
| `notion_update_page` | ✅ 改名 / **移入回收站**（`in_trash`）都成功，测试页已清理 |

### 9.3 实测抓到的两个 API 坑（都写进代码与测试）

1. **create page 不接受顶层 `title`**：标题必须放进 `properties`，且键名是"父级里 title 属性的名字"——
   普通页面固定 `title`；数据库（data source）下可能是「名称」，所以先在 data source schema 里找 `type=title` 的属性名。
2. **`archived` 字段已被移除**：2026-03-11 起要用 **`in_trash`**；对外仍保留 `archived` 参数名（模型习惯），
   内部翻译成 `in_trash` 发出去（单测钉住"不发 archived"）。

### 9.4 现状与下一步

- 现在：NOTION 连接器可用（读 + 搜 + 查库 + **新建/追加/改属性/归档**），写操作走审批卡、审计、白名单、
  `allowWrite` 开关。
- **P1 跳转授权（OAuth 公开连接）：已决定不做**（2026-10-30）。
  理由：当前 token 模式已经满足需求（每人粘自己的令牌、按用户隔离、写入走审批+白名单+审计），
  而 OAuth 需要额外的前置条件（登录安全增强 + 外网 https + 在 Notion Developer portal 注册公开连接），
  收益（少一次粘贴）不值这些成本。第 8 节的流程与前置条件保留在此，将来若要接可直接照做。
- 因此「加外网 https」「登录安全增强」不再是接入 Notion 的前置条件，按你自己的节奏推进即可。

### 9.5 维护提示（token 模式）

- 令牌失效时（Notion 里撤销/重新生成），Agent 会明确返回「Notion 令牌无效或已被撤销：请到 Developer portal 重新生成」
  —— 到「能力 → 连接器 → 我的 Notion」编辑，**粘贴新令牌**保存即可（旧令牌留空=不改）。
- 换令牌不会影响页面/数据库白名单与 Agent 绑定。
- 想让 Agent"以你自己身份操作、不用给页面添加连接"，可以在 Developer portal 建**个人访问令牌（PAT）**替换当前令牌，
  工具与行为完全不变（`测试连接` 会显示当前是 PAT 还是集成令牌）。
