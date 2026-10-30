# 接入钉钉文档调研（2026-10-30）

结论先说：**可以接，走「原生连接器」（钉钉开放平台 REST API），和 Notion 连接器同一套框架，约 3 天。**
但钉钉这边和 Notion 有一个**本质差异**：钉钉用的是**企业应用凭据 + operatorId（以谁的身份操作）**，
不是"每人一个令牌"。所以"用户隔离"要靠 operatorId 与白名单，而不是靠各自的 token。

> 本机与服务器环境都实测过：`api.dingtalk.com`、`oapi.dingtalk.com` 均可访问（TLS OK）。

---

## 1. 实测到的接口面（都是真打接口确认存在，401/400 而非 404）

### 1.1 知识库（wiki，`/v2.0/wiki/*`）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/v2.0/wiki/workspaces` | 知识库列表 |
| GET | `/v2.0/wiki/mineWorkspaces` | 我参与的知识库 |
| GET | `/v2.0/wiki/workspaces/{workspaceId}` | 知识库详情 |
| GET | `/v2.0/wiki/nodes/{nodeId}` | 节点（文档/文件夹）详情 |
| GET | `/v2.0/wiki/nodes` | 节点列表 |
| GET | `/v2.0/wiki/nodes/queryByUrl` | **通过 URL 查节点**（用户粘链接就能定位） |
| POST | `/v2.0/wiki/nodes/batchQuery` | 批量查节点 |

### 1.2 钉钉文档（doc，`/v1.0/doc/*`）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/v1.0/doc/workspaces` | 文档空间（团队空间/知识库）列表 |
| POST | `/v1.0/doc/workspaces/infos/query` | 批量查空间信息 |
| POST | `/v1.0/doc/workspaces/{workspaceId}/docs` | **在工作空间里新建文档** |
| DELETE | `/v1.0/doc/workspaces/{workspaceId}/docs` | 删除文档 |
| POST | `/v1.0/doc/workspaces/docs/infos/query` | 批量查文档信息 |
| GET | `/v1.0/doc/workspaces/docs/recentEditDocs` | 我最近编辑的文档 |
| GET | `/v1.0/doc/workspaces/{workspaceId}` + 节点 | 空间下的节点树 |
| GET | `/v1.0/doc/suites/documents/{docKey}/blocks` | **读文档正文（块列表）** |
| POST | `/v1.0/doc/suites/documents/{docKey}/blocks` | **插入块**（正文写入） |
| POST | `/v1.0/doc/suites/documents/{docKey}/blocks/{blockId}/paragraph/appendText` | 往某段**追加文字** |
| PATCH | `/v1.0/doc/suites/documents/{docKey}/blocks/{blockId}` | **修改块** |
| POST | `/v1.0/doc/suites/documents/{docKey}/overwriteContent` | **整篇覆盖内容** |
| DELETE | `/v1.0/doc/suites/documents/{docKey}/blocks/{blockId}` | 删除块 |
| GET | `/v1.0/doc/suites/documents/{docKey}/export/snapshot` | 导出快照（PDF/图片，用于"发给我看看"） |
| GET | `/v1.0/doc/{dentryUuid}/export` | 导出文件 |
| GET | `/v1.0/doc/suites/documents/{docKey}/comments` | 评论列表 |
| POST | `/v1.0/doc/documents/{docId}/comments` | 加评论 |

### 1.3 钉钉表格（workbook，`/v1.0/doc/workbooks/*`）

`appendRows`、`batch`、`createSheet`、条件格式、筛选视图…… **接口非常多**（同等量级的一个子产品）。
建议放 P2，先不做（除非你明确要"往在线表格里写数据"）。

### 1.4 鉴权模型（关键差异）

- 请求头：`x-acs-dingtalk-access-token` = **企业应用** access_token
  （用我们已有的 `appKey/appSecret` 换，`/v1.0/oauth2/accessToken`，2 小时有效、缓存复用）；
- 绝大多数文档接口还要传 **`operatorId`**（query 或 body）：表示"以哪个员工的身份操作"，
  **权限完全取决于这个人的可见范围**（能不能看这个知识库/文档，由他本人的权限决定）；
- 也就是说：钉钉这边**没有"每人一个令牌"**这种模式，隔离手段是
  ① operatorId 用谁的；② 连接器里的知识库/文档白名单。

---

## 2. 三条路线

### 路线 A：原生连接器（推荐）

新增 `DINGTALK_DOC` 连接器类型，凭据模型：
**平台级 appKey/appSecret（管理员配一次）+ 每用户 operatorId + 白名单**。

| 优点 | 缺点 |
|---|---|
| 和邮件/Notion 同一套框架：动态表单、tag 白名单、启停、审计、聊天页入口全部复用 | 需要自己实现块转换器（钉钉块 ≠ Notion 块） |
| 不需要跳转授权（你已明确不做）、不需要每人配令牌 | operatorId 的准确获取需要设计（见 §4） |
| 可以复用**现有的钉钉机器人应用**，只加文档权限点 | 权限点要在开放平台控制台勾选（需你操作一次） |

### 路线 B：本地 MCP

钉钉**没有官方 MCP server**（社区有零散实现，质量/维护不可控）；且我们镜像没有 Node。
→ **不推荐**。

### 路线 C：复用现有钉钉机器人渠道，把文档能力直接塞进渠道层

技术上就是把 A 的工具挂到现有 `channel_app` 凭据上，但会打破"连接器=可绑定/可启停/可审计"的模型，
而且机器人渠道是给 IM 用的，混在一起以后不好治理。→ **不推荐**，A 更干净。

---

## 3. 推荐方案 A 的具体设计

### 3.1 类型描述（新增 `DINGTALK_DOC`）

| 字段 | 控件 | 说明 |
|---|---|---|
| `operatorId` | TEXT | 以谁的钉钉身份操作（userId/unionId）。**留空=自动**用"该用户在钉钉机器人里的 staffId"（见 §4） |
| `defaultWorkspaceId` | TEXT | 默认知识库/空间 id（新建文档时的落点） |
| `allowedWorkspaceIds` | TAGS | 知识库/空间白名单（空=不限） |
| `allowedDocKeys` | TAGS | 文档白名单（空=不限；可粘文档链接，自动解析 docKey/dentryUuid） |
| `allowWrite` | SELECT | 允许写：是 / 否（只能读） |
| `maxBlocks` | NUMBER | 单篇最多读多少块（默认 300） |

凭据**不在连接器里填**（用平台配置 `myagent.connector.dingtalk-doc.app-key/app-secret`，
默认直接复用 `channel_app` 里那个钉钉应用的凭据；也可以用环境变量覆盖）。

### 3.2 工具（P0：8 个）

| 工具 | 读写 | 对应接口 |
|---|---|---|
| `dt_list_workspaces` | 只读 | `/v2.0/wiki/mineWorkspaces` + `/v1.0/doc/workspaces` |
| `dt_search_docs` | 只读 | `/v1.0/doc/workspaces/docs/infos/query`（按关键词/空间） |
| `dt_recent_docs` | 只读 | `/v1.0/doc/workspaces/docs/recentEditDocs`（"我最近改过的"） |
| `dt_get_doc` | 只读 | `blocks` → **转成 Markdown**（标题/段落/列表/待办/引用/代码/图片链接） |
| `dt_get_doc_by_url` | 只读 | `/v2.0/wiki/nodes/queryByUrl` → 再读内容（用户直接粘链接） |
| `dt_create_doc` | **写** | `/v1.0/doc/workspaces/{wsId}/docs`（标题 + Markdown 正文） |
| `dt_append_content` | **写** | `blocks` 插入 / `appendText` 追加（**追加**，不动原文） |
| `dt_overwrite_content` | **写** | `overwriteContent`（整篇替换，风险高 → 强制审批） |

P2 可选：`dt_update_block`、`dt_delete_block`、`dt_export_pdf`、`dt_comment`。

### 3.3 治理（全部复用现有机制）

- 写工具 `isReadOnly=false` → `REQUIRE_APPROVAL` 的 Agent 自动弹审批卡；
- `allowWrite=否` 时写工具直接拒绝；白名单外直接拒绝（提示怎么加）；
- 所有写操作落 `connector_action_log`（谁、哪个文档、动作、成功/失败）；
- 限流：钉钉按应用 QPS 限制（文档类通常较低），客户端串行 + 最小间隔 + 429/限流码退避。

### 3.4 块转换（这次的真正工作量）

钉钉文档块结构大致是：

```json
{"blockType":"paragraph","paragraph":{"elements":[{"textRun":{"content":"正文"}}]}}
```

与 Notion 的 `{type, paragraph:{rich_text:[...]}}` 不同 → 需要一个独立转换器
`DingTalkDocMarkdown`（Markdown ↔ 钉钉块子集：段落/标题/列表/待办/引用/代码/分割线 + 行内样式/链接）。
按 Notion 那次的经验，这块含单测约 1 天。

---

## 4. operatorId 从哪来（这是方案里最需要你确认的一点）

三个可选策略，可组合：

| 策略 | 说明 | 体验 |
|---|---|---|
| **A. 自动映射（推荐）** | 用户在平台上跟钉钉机器人说过话 → `channel_peer.peer_key` 就是他的 `staffId`（钉钉 userId），
通过 `channel_peer.chat_session_id → chat_session.user_id` 就能把"平台用户 → 钉钉 userId"对上 | 用户什么都不用填 |
| B. 手填 | 连接器里填 `operatorId`（钉钉通讯录里能查到） | 需要用户操作一次 |
| C. 平台默认 | 配置一个默认 operatorId（例如管理员），所有操作以他身份进行 | 简单，但权限/审计都算管理员 |

> 建议：**A 优先、B 兜底**（自动映射拿不到时提示用户填），并保留白名单做第二道保险。

---

## 5. 需要你在钉钉开放平台做的事（一次性）

1. 打开现有那个钉钉应用（就是机器人用的那个）→「权限管理」→ 勾选**文档/知识库的读与写权限**
   （控制台里的权限点名称以实际为准，一般形如「知识库文档的查看/编辑」「文档的查看/编辑」）；
2. 确认应用的**可见范围**包含要用文档的用户；
3. 其余不用改（不需要新增回调地址、不需要跳转授权）。

---

## 6. 工作量与验收

| 项 | 估时 |
|---|---|
| 类型描述 + 平台凭据（复用 channel_app）+ token 缓存 | 0.5 天 |
| `DingTalkDocClient`（限流/错误映射/docKey 与链接解析/块分页） | 0.5 天 |
| `DingTalkDocMarkdown`（Markdown ↔ 钉钉块） + 单测 | 1 天 |
| 8 个工具 + 白名单/allowWrite/审计 | 0.5 天 |
| operatorId 自动映射（channel_peer → staffId） | 0.25 天 |
| 真机联调（需要你给一个测试知识库/文档） | 0.5 天 |

**验收清单**：测试连接（显示能访问几个知识库/文档）→ 列知识库 → 搜文档 → 读正文（Markdown）→
新建文档 → 追加内容 → 整篇覆盖（弹审批）→ 白名单拦截生效 → 停用后 Agent 说"已停用，怎么开"。

---

## 7. 待你确认

1. **要接的是哪个**：钉钉文档（新版 doc）/ 知识库（wiki）/ 钉钉表格（workbook）/
   还是钉盘里的文件？**我建议先做「文档 + 知识库」，表格放 P2**。
2. **凭据复用**：直接复用现有钉钉机器人那个应用（你再给它加文档权限），可以吗？
   还是想单独建一个应用？
3. **operatorId**：用「自动映射（跟机器人说过话就有）+ 手填兜底」这个组合可以吗？
4. **写入范围**：新建文档、追加内容、整篇覆盖 —— 三个都要吗？（我建议前两个默认开，**整篇覆盖**默认要求审批）
5. 有没有一个**测试知识库/文档**可以先授权给我联调（我会像 Notion 那样测完把测试文档删掉）？
