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

## 验收标准

- 完成一轮会触发 Memory Flush 的对话后，长期记忆内容能在数据库表中用 SQL 查到（附 SQL 与结果）。
- 会话结束/落库后，`agent_state` 中存在该 `(agentId, sessionId)` 的状态行（附 SQL）。
- 同一 Agent 的下一轮对话仍能读到此前写入的长期记忆（从库读取），行为与磁盘版一致（附对话证据）。
- 数据库是记忆与会话状态的有效存储：即使工作区中对应的记忆/状态文件不存在，功能仍正常（附反证实验）。
- 门禁：新增依赖与实现只使用官方 SPI（G8）；配置类包不引入 `io.agentscope`（G2）；DDL 只经 Flyway（G5）。
