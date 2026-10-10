# Backlog

门禁 G7：任务列表（`docs/任务列表.md`）未列出的功能一律不实现，只登记在此，不进入当前迭代。

## 实现期发现（值得后续做，但不属于本次范围）

| 日期 | 来源 | 需求 | 备注 |
|---|---|---|---|
| 2026-10-10 | Phase 5 实测 | Skill 内容**按需加载**（渐进式披露）：如需让模型总是看到完整 Skill 内容，需评估 `SkillCurator` / `SkillUsageStore` 或自定义 prompt provider | 当前行为是官方设计，不是缺陷 |
| 2026-10-10 | Phase 6 实测 | MCP 工具目前以**裸工具名**注册；若两个 MCP Server 暴露同名工具，需要评估命名冲突策略（官方 `Toolkit.createToolGroup`） | 当前未做冲突处理 |
| 2026-10-10 | Phase 9 实测 | `AguiSseSanitizer` 目前无差别去掉所有 null 字段；若后续某字段确实需要显式 null 语义，需改为白名单式 | 当前与官方客户端 `omitOptionalNulls` 行为一致 |
| 2026-10-10 | Phase 4 实测 | `AgentFactory` 按 agent 主键缓存单个 HarnessAgent（多线程共用）；官方有 `AgentResolver.hasMemory` 语义，可按线程/会话细化 | |
| 2026-10-10 | Phase 9 | AgentScope 自建 `agentscope` schema（官方扩展管理）；如需纳入统一迁移管理，需评估 | 见 commit 中 G4 边界说明 |

## 用户可感知但本次未实现（明确超出任务列表）

| 日期 | 需求 | 备注 |
|---|---|---|
| 2026-10-10 | 管理台前端页面（模型 / Agent / Skill / MCP 的增删改查界面） | 任务列表 Task 4.3 只要求「聊天页」；其余管理页在方案第 7 节「前端结构与页面」中列出，但任务列表未排期。后端接口（`/api/model-providers`、`/api/models`、`/api/agents`、`/api/skills`、`/api/mcp-servers`）均已实现并有 curl 证据 |
| 2026-10-10 | 前端代码分割（build 有 >500KB chunk 警告） | 仅警告，不影响门禁 |
| 2026-10-10 | RAG / 向量检索、语音、A2A 多 Agent 协议、多租户、国际化 | 方案第 11 节 G7 明确排除 |
