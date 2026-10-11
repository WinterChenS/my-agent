# chat-run-control

## Purpose

让用户能在 Agent**生成过程中主动停止**，并且真正让服务端停下来（而不是只断开前端连接、后端继续跑完空烧 token）。

## 现状（实测）

- 前端只有"发送"，生成中只能等（`busy` 期间输入与发送都禁用）。
- 后端 SSE 在客户端断开后并不会中断该 run：框架会继续把这一轮跑完。
- 框架提供**官方中断入口**（门禁 G8 允许直接用）：`HarnessAgent.interrupt()`、`interrupt(RuntimeContext)`、
  `interrupt(agentId, sessionId)`、`interrupt(..., Msg)`；`ReActAgent` 侧还有 `saveAgentState`/`ShutdownInterrupted` 等配套状态。

## 设计

### 前端

- 生成中（`status=running` 或 `busy`）把「发送」按钮换成「**停止**」（同位置、同样的尺寸，避免布局跳动）。
- 点「停止」：① 立刻 `abort` 在途的 SSE 请求；② 调后端停止入口；③ 状态回到可输入；④ 该条助手气泡保留已生成内容并标注「已停止」。
- 停止后可继续对话（下一个 run 正常发起）。

### 后端

- 新增 `POST /api/sessions/{id}/interrupt`（JWT + 属主校验）：按会话的 `threadId` 与当前用户找到运行中的 Agent 实例，
  调用官方 `interrupt(...)`；返回 `{interrupted: true|false}`（没有运行中的 run 时返回 false，不报 500）。
- SSE 断开兜底：`SseEmitter` 的 onCompletion/onError/onTimeout 里同样触发中断，避免用户关页面后继续烧 token。
- 落库：被停止的这一轮把已生成的助手文本 + 停止标记写入 `chat_message`（可回放），并记录一次 token 用量（若框架已产生用量）。

### 边界

- 停止只影响当前 run，不影响会话后续消息。
- 停止期间已经发生的工具调用结果保留在气泡里（不隐藏历史）。
- 与 HITL 互不干扰：等待审批的 run 本来就停着，停止不改变审批记录状态。

## 验收标准

- 生成中点「停止」：前端立即回到可输入状态；后端日志/状态显示该 run 已中断；**此后不再产生新的 token 消耗**。
- 已生成内容保留并可回放；气泡上有「已停止」标识。
- 生成中直接关闭页面/断开连接：服务端在数秒内自行中断该 run（不再继续跑完）。
- 没有运行中的 run 时调停止接口：返回业务上的 `{interrupted:false}`，不是 500。
- 停止后继续提问：新的 run 正常完成，历史不丢。
