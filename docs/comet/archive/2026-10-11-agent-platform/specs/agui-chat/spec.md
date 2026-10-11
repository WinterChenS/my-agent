# agui-chat

## Purpose

提供 AG-UI 协议的聊天主链路：SSE 事件流、会话与消息持久化、以及审批卡 / 确认卡 / 映射卡 / 工具调用卡的前端渲染契约。

## 端点

`POST /agui/run` —— SSE（`text/event-stream`），由 AgentScope 官方 AG-UI 扩展提供，需 Bearer JWT。

行为：

1. 接收 AG-UI `RunAgentInput`（含 `threadId`、`runId`、`messages` 等）。
2. 按 `threadId` 定位或创建属于当前用户的 `chat_session`；按输入中的 agent 标识或会话关联解析目标 Agent。
3. 经 `AgentFactory.build` 构建 Agent，将 AgentScope 事件流透传为 AG-UI 事件。
4. run 结束时把用户消息与 assistant 最终文本落 `chat_message`（含 `seq` 顺序与 `agui_meta`）。
5. 中途客户端断开不得抛未捕获异常，服务必须能继续处理后续请求。

## 会话与消息 API

| 方法 | 路径 | 行为 |
|---|---|---|
| GET/POST | `/api/sessions` | 当前用户会话列表 / 创建 |
| DELETE | `/api/sessions/{id}` | 删除（仅属主） |
| GET | `/api/sessions/{id}/messages` | 历史消息（仅属主） |

- 非属主访问他人会话或消息返回 403 / 404。

## 事件 → 卡片映射（冻结契约）

| AG-UI 事件 | 前端呈现 |
|---|---|
| `TEXT_MESSAGE_START` / `TEXT_MESSAGE_CONTENT` / `TEXT_MESSAGE_END` | Markdown 助手气泡，逐字流式 |
| `TOOL_CALL_START` / `TOOL_CALL_ARGS` / `TOOL_CALL_END` | 工具调用卡（可折叠：工具名 + 参数 + 结果） |
| `STATE_SNAPSHOT` / `STATE_DELTA` | 预留，仅记录日志 |
| 权限系统审批事件 | 审批卡 |
| `CUSTOM` / `confirm_card` | 确认卡 |
| `CUSTOM` / `mapping_card` | 映射卡 |
| `RUN_STARTED` / `RUN_FINISHED` / `RUN_ERROR` | 运行状态条 / 错误气泡 |
| 其他未知 `CUSTOM` | 降级为 JSON 折叠卡，界面不得崩溃 |

CUSTOM 事件 `data` 结构（两端写死，视为接口契约）：

```ts
interface ConfirmCardData { title: string; message: string; options: {label: string; value: string}[]; requestId: string }
interface MappingCardData { title: string; columns: {key: string; label: string}[]; rows: Record<string, unknown>[] }
```

卡片组件统一位于 `frontend/src/components/cards/`，由 shadcn/ui 组合实现。

## HITL 审批

- `permission_mode = REQUIRE_APPROVAL` 时，权限系统拦截工具调用：写 `approval_record(PENDING)` 并推送审批卡事件。
- `POST /api/approvals/{id}/decide`：`{decision: APPROVED|DENIED, reason?}` → 唤醒 / 终止该会话的执行。
- 状态流转：`PENDING → APPROVED | DENIED | EXPIRED`。
- 仅该会话属主可 decide，否则返回 403。
- 等待与恢复逻辑来自 AgentScope 权限系统，不得自研（`hitl/` 包不得出现 `Thread.sleep` / `CountDownLatch` 式自研等待）。

## 前端

- `lib/agui/`：基于 `@ag-ui/client` 的 HttpAgent（fetch 注入 Bearer）+ 事件→消息树 reducer + 断线处理。
- `ChatPage`：左侧会话列表（`/api/sessions`），右侧气泡流与卡片、底部输入框；历史经消息接口回放。
- 后端不可用 / 网络断开时显示错误态并可重试，页面不白屏。

## 边界

- 后端不自行实现事件协议或推理循环；AG-UI 端点行为以官方扩展为准（门禁 G3 / G8）。

## 验收标准

- `curl -N -X POST /agui/run` 可见 `RUN_STARTED → TEXT_MESSAGE_* → RUN_FINISHED` 事件序列。
- 跑完后 `chat_session` / `chat_message` 有对应行。
- 中途断开 SSE 服务不崩溃。
- 前端发消息流式渲染、刷新后历史仍在；reducer 单测覆盖 `TEXT_MESSAGE_*` 与 `TOOL_CALL_*` 各至少一例。
- `REQUIRE_APPROVAL` 场景：批准后工具真实执行并继续，拒绝后收到拒绝反馈；非属主 decide 返回 403。
- `demo-agent` 能渲染 confirm / mapping / tool-call 三种卡片，回传后 run 继续。
