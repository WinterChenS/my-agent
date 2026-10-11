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
| 2026-10-10 | ~~管理台前端页面（模型 / Agent / Skill / MCP 的增删改查界面）~~ | **已补齐**（用户指出遗漏后修复）：四个管理页已按技术方案第 7 节实现并浏览器实测通过 |
| 2026-10-10 | 前端代码分割（build 有 >500KB chunk 警告） | 仅警告，不影响门禁 |
| 2026-10-10 | RAG / 向量检索、语音、A2A 多 Agent 协议、多租户、国际化 | 方案第 11 节 G7 明确排除 |
| 2026-10-10 | 用户管理页（ADMIN 创建/停用用户） | 任务列表未排期；后端只有 `/api/auth/*` 与种子 admin，尚无用户 CRUD 接口 |

## 停止执行（用户 2026-10-11 提出，待作为增量排期）

- **需求**：Agent 正在生成时，用户要能"停止执行"——不再只等它跑完。
- **可行性（已核对）**：框架有官方中断 API `HarnessAgent.interrupt()` / `interrupt(RuntimeContext)` / `interrupt(agentId, sessionId)`，
  因此可以做到**服务端真的停下**（而不是只断开前端 SSE），不需要自研中断逻辑（G8）。
- **拟定范围**：前端在生成中把"发送"换成"停止"按钮并中断在途 SSE；后端新增停止入口调用官方 interrupt；
  客户端断开时也自动中断，避免白烧 token；气泡上标注"已停止"，部分内容保留，之后仍可继续对话。
- **状态**：等当前修复轮次验收通过后，走一次需求修订（Phase 12）再实现。
