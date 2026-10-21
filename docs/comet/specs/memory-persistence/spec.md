# memory-persistence

## Purpose

把 Agent 的**长期记忆**与**框架会话状态**从「只落磁盘」改为「落 PostgreSQL」，用官方 SPI 的存储后端替换，不重写框架的记忆/状态逻辑（门禁 G8）。

## 现状（实测，2026-10-11）

| 内容 | 现在存哪 | 在数据库吗 |
|---|---|---|
| 用户可见对话消息 | PostgreSQL `chat_session` / `chat_message`（实测 183 条） | ✅ 在 |
| 框架会话状态 | `.myagent-workspace/<userId>/agents/<agentId>/sessions/<threadId>.jsonl`（实测 1.8 MB） | ❌ 磁盘 |
| 框架长期记忆（Memory Flush） | `.myagent-workspace/<userId>/memory/YYYY-MM-DD.md`（实测 20 KB） | ❌ 磁盘 |
| 库中的 memory 表 | 无 | ❌ 不存在 |

相关官方扩展点（均已核对存在）：`io.agentscope.core.state.AgentStateStore`（官方实现 `JsonFileAgentStateStore` / `InMemoryAgentStateStore`，`HarnessAgent.Builder.stateStore(...)` 可替换）、`io.agentscope.harness.agent.filesystem.remote.store.BaseStore`（官方 SPI，方法仅 `get/put/putIfVersion/search/delete`）、`io.agentscope.harness.agent.filesystem.RemoteFilesystem`、`NamespaceFactory`、`WorkspaceManager(Path, AbstractFilesystem, …)`。

## 设计（D23，用户选 A）

### 一、会话状态 → PostgreSQL 版 AgentStateStore

- 实现 `io.agentscope.core.state.AgentStateStore` 的 PG 版：`save`（单条/批量）、`get`、`getList`、`exists`、`delete`、`listSessionIds`、`supportsVersioning`；`State` 以 JSONB 存储。
- 经 `HarnessAgent.Builder.stateStore(...)` 注入，替换默认的 JsonFile 实现。
- 表 `agent_state`：`id`、`agent_id`、`session_id`、`state_key`、`payload jsonb`、`version bigint`、`created_at`、`updated_at`，唯一索引 `(agent_id, session_id, state_key)`。

### 二、长期记忆 → 官方 RemoteFilesystem + PG 版 BaseStore

- 实现官方 `BaseStore` 的 PG 版 + 官方 `NamespaceFactory`（按 `RuntimeContext` 生成命名空间：用户/租户维度）。
- 用官方 `RemoteFilesystem` 包装后交给 `WorkspaceManager`/Agent，使框架的记忆文件读写实际落到库。
- 表 `memory_store`：`id`、`namespace_path text`、`item_key text`、`payload jsonb`、`version bigint`、`created_at`、`updated_at`，唯一索引 `(namespace_path, item_key)`；并发用 `putIfVersion` 做乐观锁。

### 三、实现期必须先验证的一点（否则启用备选）

实测 `WorkspaceManager.appendUtf8WorkspaceRelative` 走 `filesystem` 抽象，但 `readManagedWorkspaceFileUtf8` / `readMemoryMd` 部分路径直接解析本地 `Path`。因此落地前必须先验证「记忆的读与写是否都经过可替换的 filesystem 抽象」：

- **能覆盖** → 用 `RemoteFilesystem` + PG `BaseStore`（本设计原方案）。
- **不能覆盖** → 退回备选：记忆仍由框架写工作区，但在 `MemoryFlushManager`/run 结束后把记忆内容**写穿到库**（`memory_store` 表），并把读取路径改为优先取库（官方 SPI 不变，仍不重写框架逻辑）。

两种路线都必须满足下面的验收标准（用户关心的是「在数据库里、Agent 仍读得到」，而不是具体走哪条路）；实现时在任务记录里写明最终采用哪条及原因。

## 边界

- 不改框架的记忆生成 / 合并 / 保留策略；不引入向量检索与 embedding（非目标）。
- `chat_message` 仍是用户可见对话的**权威记录**（记忆表不替代它）。
- 记忆内容可能含用户隐私：不写进日志；只对属主可见；沿用门禁 G4（不落凭据）。

## 记忆检索的坑与平台补丁（Task 11.3，2026-10-20）

用户报「永久记忆不生效」：会话 A 说「以后都叫我主人」→ 调了 `memory_save`；新开会话问「怎么称呼我」→ 调了 `memory_search`，回答「没找到」。

**实测结论：记忆的存/取/注入都是好的，坑在「查询参数」。**

| 环节 | 实测 | 结论 |
|---|---|---|
| 写 | `memory_store` 里当天台账 `user/1` `/2026-10-20.md` 含「主人」；`MEMORY.md` 在工作区持续更新 | ✅ 正常 |
| 注入 | 抓发给模型的请求体：系统提示 `<memory_context>` 里已有「用户希望被称为「主人」」 | ✅ 正常 |
| 检索 | 框架 `MemorySearchTool.keywordSearch` = `Pattern.compile(Pattern.quote(query))`，**整条 query 当一个字面量**匹配每一行 | ⚠️ 脆弱 |

实测同一条记忆、同一天：

- `memory_search(query="怎么称呼我")` → `No matching memories found`
- `memory_search(query="主人")` → `Found 37 matches`（含「用户希望被称为「主人」」）

数据侧佐证：「主人」出现在 8/10 个台账文件，而「称呼」只在 2/10、且当天台账里没有 —— 所以用「称呼」搜当天记忆也会 0 命中。

**第一轮处理（不够）：** 平台在系统提示尾部追加「长期记忆用法」指引（`AgentFactory.withMemoryGuidance`）。
用户复测仍失败（模型照样用多词查询）——**提示词管不住模型**。

**最终处理（确定性，走官方钩子）：** 新增 `MemoryHintMiddleware implements MiddlewareBase`
（经 `HarnessAgent.Builder.middleware(...)` 接入），实现 `onSystemPrompt`：
每轮读该用户 `MEMORY.md`，把「用户偏好」小节要点 + 称呼/偏好类条目（≤12 条、≤900 字）拼成一段
**放在系统提示最前面**，并声明“以上已确认，回答问题以此为准，不要再声称没有记录”。

- 位置最关键：`<memory_context>` 是“工作区参考资料”（靠后、体量大），模型会忽略；
  指令区开头的一句话它才当回事。
- 实测（deepseek-v4.1-flash）：改造前答“没有设过任何称呼”，改造后答「我称呼您为「主人」」。
- 只读框架维护的 MEMORY.md、只在提示层做提示，不重写框架记忆逻辑（G8）。
- 也试过用权限层 DENY `memory_search`：能拦住（返回 `Permission denied by rules`），但**光堵不解决问题**，故最终没有保留。

**原提示词层指引（保留，作为补充）：** 平台在系统提示尾部追加一段「长期记忆用法」指引（与既有 `withCardGuidance` 同一手法，`AgentFactory.withMemoryGuidance`）：

1. 先看**已注入**的 `<memory_context>`，不要一上来就搜索；
2. `memory_search` 的 `query` 只传**短关键词**，**不要传整句问句**；
3. 命中 0 条**不等于没有记忆**：换更短/同义的关键词再试，或 `memory_get` 直接读 `MEMORY.md`；
4. 保存只走 `memory_save`，不要 `write_file`/`edit_file` 改记忆文件。

已知局限：框架的 `memory_search` 仍是整串字面量匹配（改它要动框架，G8 不允许），所以“按需深搜旧记忆”这条路依旧不好用；
现在的兜底是**关键事实每轮前置注入**（`MemoryHintMiddleware`），只在 `MEMORY.md` 的偏好类条目范围内保证可靠。
另：记忆提醒每轮都会读一次 `MEMORY.md`（约 10KB），开销可忽略，但若将来 MEMORY.md 涨到很大，需要改成只读小节。

## 记忆可视化与编辑（Task 11.4，2026-10-20）

用户要求「记忆可视化，可以编辑」。落地为：前端 `/memory` 页面 + `GET/PUT/DELETE /api/memory` 三个接口。

**数据从哪来（沿用框架自己的布局，不另立存储）**

| 文件 | 位置 | 谁在写 |
|---|---|---|
| `MEMORY.md`（精华长期记忆） | `<workspaceRoot>/<userId>/MEMORY.md`（工作区磁盘） | 框架 consolidation |
| `memory/YYYY-MM-DD.md`（每日台账） | `memory_store` 表：`namespace_path=user/<userId>`、`item_key=/YYYY-MM-DD.md` | 框架 MemoryFlush（append） |

**接口**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/memory[?userId=]` | 清单（`MEMORY.md` 在前，台账按日期倒序；**不带正文**） |
| GET | `/api/memory/{name}[?userId=]` | 单份正文；`name` 只能是 `MEMORY.md` 或**真实日期**的 `YYYY-MM-DD.md` |
| PUT | `/api/memory/{name}[?userId=]` | 整份覆盖保存 |
| DELETE | `/api/memory/{name}[?userId=]` | 删台账；`MEMORY.md` 拒绝（只能清空正文后保存） |

- **默认只操作当前登录用户**的记忆（`userId` 取自 JWT）。`?userId=` 只给 `ADMIN` 用（见下节“谁的记忆”）。
- 文件名白名单 + `LocalDate.parse` 校验，挡掉 `../x`、`2026-13-99.md` 这类路径穿越/垃圾 key。
- 写 `MEMORY.md` 用「临时文件 + 原子移动」；写台账沿用框架 payload 形状（`content`/`encoding`/`created_at`/`modified_at`），
  `created_at` 保留原值、`modified_at` 刷新 —— 这样框架读取与后续 consolidation 都不会被格式差异搞坏。
- 实现分层：`agui/memory/MemoryAdminService` 才碰官方 SPI（`BaseStore`），`memory/controller` 只做 HTTP → DTO（门禁 G2）。

**AgentScope 官方提供了什么（实测 `javap` 2.0.3）**

- 有：`WorkspaceManager.writeUtf8WorkspaceRelative/appendUtf8WorkspaceRelative/readMemoryMd/readManagedWorkspaceFileUtf8/listMemoryFilePaths`、
  `MemoryFlushManager.flushMemories(ctx, msgs)`、`BaseStore`（`put/putIfVersion/delete`）、给模型用的 `memory_save/memory_get/memory_search` 工具。
- 没有：面向人的「编辑记忆」API；`MEMORY.md` 的正文格式归 consolidation 所有，官方只暴露文件级读写。
- 所以我们**自己加一层薄的读写服务**（复用官方存储布局），不重写记忆生成/合并逻辑。

**是否立即生效（实测）**

- 修改 `MEMORY.md` 后，**下一轮**对话的系统提示里立刻能看到新内容（抓包证据：写入 `MEM-VIS-MARK-…` 后下一轮 system prompt 命中该标记；`MemoryHintMiddleware` 每轮都会读它）。
- 无需重启后端、无需清缓存（记忆是文件/库，Agent 每轮重新读）。
- 注意两个边界：① 框架只注入**最近若干天**的台账（写很久以前的日期不会进上下文，实测 `2020-01-01.md` 不进）；
  ② consolidation 之后可能把台账内容合并/重排进 `MEMORY.md`，人工编辑的内容不会被阻止，但可能被再次整合。

**前端页面**

- `/memory`：左侧清单（`长期记忆` 置顶 + 每日台账按日期倒序，带大小），右侧「预览 / 编辑」双模式；
  有未保存改动时显示「未保存」徽标，切换文件会二次确认，支持 `Ctrl/Cmd+S` 保存；台账可删除（二次确认）。
- 单测：`src/lib/memory/files.test.ts` 7 项（排序/汇总/未保存判定/标签）；e2e：`verify-memory-page.spec.ts`
  （造假日期台账 → 清单 → 预览 → 编辑 → 保存 → **接口复查存储已变** → 删除，全程不碰真实记忆）。

## 谁的记忆：默认自己，超管可看别人（Task 11.5，2026-10-21）

用户要求：「超管可以检索其他用户的，默认展示自己的；别的用户只能看自己的」。

**判定规则（`MemoryController.resolveTargetUser`，单一闸口）**

| 调用者 | 请求 | 结果 |
|---|---|---|
| 任何人 | 不传 `userId` / 传自己的 id | 自己的记忆 |
| `ADMIN` | 传别人的 id | 别人的记忆（读 / 写 / 删都一样） |
| 普通 `USER` | 传别人的 id | **403**「只能查看自己的记忆」 |

- 为什么权限判定必须写在控制器里：`/api/memory/**` **不在** `SecurityConfig.CONFIG_PATHS`
  （那条只管配置类接口的写操作），安全配置兜不住跨用户读写。
- 为什么普通用户不回退成“静默改成自己”：那样前端会以为展示的是别人的记忆，属于更隐蔽的错误；明确 403 更诚实。
- 存储侧不用改：`MemoryAdminService` 本来就是“传谁的 userId 就操作谁的记忆”
  （`<workspaceRoot>/<userId>/MEMORY.md` 与 `memory_store` 的 `namespace_path=user/<userId>`），
  所以跨用户只是把目标 id 传下去。
- 前端：只有 `role === "ADMIN"` 才渲染「搜用户」框（`MemoryUserSearch`），**默认就是自己**；
  切到别人时左侧提示「正在查看 X（#id）的记忆 —— 编辑/删除都会改到对方的记忆」，
  切换用户与切换文件一样有未保存二次确认。普通用户看不到这个框。

**为什么是搜索框而不是用户下拉框（用户当场纠正）**

第一版做成了「把用户列表一次拉下来塞进 `<select>`」，被用户否掉：下拉框里只看到几个显示名
（「我自己 / 内部用户 / 外部用户」），既不能搜也不能翻，而排查问题的人手里通常只有
「用户 #37」这种 id（日志、审计、工单里都是 id）。现在的做法：

- 输入即搜（250ms 防抖），走 `GET /api/users?keyword=&size=8`，结果列表点一下即选中；
- 支持三种输入：用户名（模糊）、显示名（模糊，真实数据里常为中文）、用户 ID（`37` 或 `#37`，**精确**）；
- 搜不到就明说「没找到匹配的用户」，不假装选中；已选中的用户在输入框左侧显示为 chip，点 × 回到自己。
- 后端配合：`UserService.list` 的 `keyword` 在**纯数字（或 `#` + 数字）**时额外加 `id = ?` 条件
  （`UserService.parseId`，非数字/超长一律当普通关键字，不抛异常）。

**取证**

- 后端：`MemoryControllerTest` 5 项（默认自己 / 传自己 / 超管跨用户 / 普通用户 403 / 角色缺失按普通用户）；
  `UserServiceTest` 新增“纯数字 / `#37` 按 ID 精确匹配”与 `parseId` 边界（`张三`、`37abc`、空、超长）。
- 前端单测：`src/api/memory.test.ts` 3 项（四个接口的 `?userId=` 拼装，漏拼会导致“选了别人却拿回自己的”）；
  `MemoryUserSearch.test.tsx` 4 项（防抖只打一次、点结果回调并清空、空结果、chip 清除）。
- e2e `verify-memory-cross-user.spec.ts`（**真实浏览器、真实第二个账号**）：
  对端账号在浏览器里登录它自己 → 跟 Agent 聊一轮 → 模型调 `memory_save` 写进**它自己的** `MEMORY.md`
  → 普通用户越权 403 → 超管在页面上按**中文显示名 / 用户名 / ID** 分别搜到它并看到那份记忆
  → 对端自己进记忆页只看到自己的且没有搜人入口；
  截图 `docs/acceptance/task-11.5-memory-admin-cross-user.png`。

**实测踩到的坑（已处理，别再重踩）**

1. **Vite dev server 会端着旧模块不放**：改了 `MemoryPage.tsx` 但 `:5173` 一直返回旧的
   `<select>` 版本（`curl :5173/src/pages/memory/MemoryPage.tsx` 能直接看出新旧），
   于是 e2e 在“找不到 `memory-user-search-input`”上卡到超时。改完前端后**重启 dev server** 再跑 e2e。
2. **`actionTimeout` 没配 = 动作永远等**：Playwright 默认只限制用例时长与断言，点击/输入卡住会
   静默吃掉全部预算（实测 600s 全花在一个永远等不到的元素上）。用例里显式 `test.use({ actionTimeout })`。
3. **`npm run dev` 可能用到别的 Node**：本机 PATH 先命中微信开发者工具的 node v16，vite 直接崩
   （`node:fs/promises does not provide an export named 'constants'`）。`scripts/run-frontend.cmd`
   已优先使用 `C:\Program Files\nodejs`。
4. **桩要每轮独占端口**：上一轮崩在收尾前会留下“僵尸桩”，它带着**上一轮的** `STUB_TOOL_ARGS` 继续监听，
   用例就会拿到旧数据并以“内容不对超时”的形式假红。`startToolStub` 现已不复用占用端口，自动往后找。
5. **有对话的用户删不掉**：`token_usage_user_id_fkey` 拦住（审计数据按设计保留），
   所以 e2e 的“对端账号”用固定账号 `e2e-mem-peer` 复用，每轮只清它的记忆与会话，不删账号。

## 验收标准

- 完成一轮会触发 Memory Flush 的对话后，长期记忆内容能在数据库表中用 SQL 查到（附 SQL 与结果）。
- 会话结束/落库后，`agent_state` 中存在该 `(agentId, sessionId)` 的状态行（附 SQL）。
- 同一 Agent 的下一轮对话仍能读到此前写入的长期记忆（从库读取），行为与磁盘版一致（附对话证据）。
- 数据库是记忆与会话状态的有效存储：即使工作区中对应的记忆/状态文件不存在，功能仍正常（附反证实验）。
- 门禁：新增依赖与实现只使用官方 SPI（G8）；配置类包不引入 `io.agentscope`（G2）；DDL 只经 Flyway（G5）。
