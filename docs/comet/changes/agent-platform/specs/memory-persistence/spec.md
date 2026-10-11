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

## 验收标准

- 完成一轮会触发 Memory Flush 的对话后，长期记忆内容能在数据库表中用 SQL 查到（附 SQL 与结果）。
- 会话结束/落库后，`agent_state` 中存在该 `(agentId, sessionId)` 的状态行（附 SQL）。
- 同一 Agent 的下一轮对话仍能读到此前写入的长期记忆（从库读取），行为与磁盘版一致（附对话证据）。
- 数据库是记忆与会话状态的有效存储：即使工作区中对应的记忆/状态文件不存在，功能仍正常（附反证实验）。
- 门禁：新增依赖与实现只使用官方 SPI（G8）；配置类包不引入 `io.agentscope`（G2）；DDL 只经 Flyway（G5）。
