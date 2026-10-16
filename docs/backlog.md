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
| 2026-10-16 | 用户反馈 + 实测 | **执行中插话（steering）**：官方支持，但入口是 `AgentSession.steer(...)`，**只存在于 2.1.0-BETA1**；我们钉的 2.0.3 没有该 API，故先摘掉入口 | 详见下方「引导（steering）」一节 |

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

## 引导（steering）：执行中插话（2026-10-16 查证后**摘掉入口**，待升级 SDK 再做）

- **需求**：Agent 正在执行时，用户补充一句话，能影响**当前这一轮**的后续步骤（不是等它跑完再发新一轮）。
- **结论：官方有这个能力，但不在我们钉的 2.0.3 里**，因此当前**无法实现**；
  已摘掉入口（保留排队/撤回），等官方 2.1.0 正式版再做。

### 官方文档（java.agentscope.io v2）怎么说

- Harness 层：`session.steer(task, "…")` / `session.steer("…")` / `session.inject("…")`。
  原文：*"Guidance becomes available at the **next reasoning step**; it does not rewrite an already-sent
  model request or change a tool call's running arguments."*；且 `steer(task, …)` 在任务已不在跑时会**拒绝**。
- 服务层：`POST $SESSION_URL/turns/$TURN_ID/steer`。要求**先读** Session/Turn `/capabilities` 与 `available_commands`，
  **再决定是否显示**取消/输入/恢复控件；并区分 `input.accepted`（已持久化）与 `input.applied`（已被运行时消费）——
  明确 *"Do not present acceptance as completed processing."*
- `interrupt()` 的官方定位是**暂停**：*"`interrupt()` is a cooperative request, not a completion acknowledgment."*

### 本地 SDK 的实证（2.0.3，我们钉的版本）

- `ReActAgent.interrupt(RuntimeContext, Msg)` 的实现只有一句：`state.interruptControl().trigger(InterruptSource.USER, msg)`。
- 全构件二进制检索：引用 `getUserMessage` 的**只有** `InterruptContext` / `InterruptControl` 自身 —— **没有任何消费方**。
- `ReActAgent.handleInterrupt` 默认行为：中断当前生成 → 追加一条**写死**的 assistant 消息
  `"I noticed that you have interrupted me. What can I do for you?"`（generateReason=INTERRUPTED）→ 保存返回。
- 实测吻合：接口曾返回 `guided=true`（调用成功），但引导的话**从未出现在之后发给模型的请求里**。
  → 拿 `interrupt` 当 steering 用是错的（等于「静默失效 + 谎报成功」），故入口已一并删除。

### 版本对比（决定可行方案）

| | 2.0.3（现用） | 2.0.4 | 2.1.0-BETA1 |
|---|---|---|---|
| `AgentSession` / `steer` / `inject` | ✗ | ✗ | ✓ |
| core 的 `io.agentscope.core.session` 整套 | ✗ | — | ✓ |
| 官方 AG-UI 扩展支持 steer | ✗ | ✗ | ✗ |

`AgentSession.steer` 签名（javap 实取）：`steer(String)` / `steer(List<Msg>)` / `steer(Task, String)` → `InputReceipt`。

### 以后要做的前置条件（不是小事）

1. **升级到 2.1.0 正式版**（现只有 `2.1.0-BETA1`），全部 agentscope 构件同步升。
2. **运行模型要换**：现在是「官方 AG-UI 适配器每请求跑一轮」；`AgentSession` 是「自己持有 agent + run 循环 + inbox/log/projection」
   （`start()` / `submit()` / `respond()` / `resume()` / `steer()` / `inject()` / `interrupt()`）。
3. **事件桥接要自己接**：官方 AG-UI 扩展（含 2.1.0-BETA1）不引用 steer/AgentSession，
   即这条路不能靠换 jar 解决，得把 AgentSession 的日志/事件译成 AG-UI 事件。
4. **UI 要按能力显示**：官方要求先看 `/capabilities` 再显示控件，不能无条件摆按钮（现有的「引导」就是无条件摆、而且静默失效）。

### 另外两条留给未来验证的经验

- 插话必须等后端这一轮**登记之后**：前端「停止按钮」只是客户端状态，它出现时后端可能还没建立 run 句柄，
  此时任何插话都必然失败（实测踩到：引导请求比 `beginRun` 早 27ms 到达）。
- 写这类验证时，dump 文件是**追加**写的：基线要在发送前取，否则"等到模型被调用"会被上一轮残留立刻满足，
  从而把「发得太早」误判成「功能不生效」（实测被这个坑骗过一次）。
