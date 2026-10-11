# subagent-events

## Purpose

把框架的**子 Agent 事件流**渲染成一张**简约、可折叠**的卡片，替代当前"每个 delta 出一张『未识别事件』JSON 卡"的刷屏行为。

## 背景（实测，官方支持）

AgentScope 的 AG-UI 扩展里有 `SubagentEventConverter`，会把子 Agent（harness `subagents=true`，工具如 `agent_spawn`/`agent_send`）的事件
转成一组 **CUSTOM** 事件（`value` 自带 `source`，形如 `<threadId>/general-purpose`）：

| CUSTOM name | value 关键字段 |
|---|---|
| `subagent.lifecycle` | `source`、`type`(AGENT_START/AGENT_END)、`name`、`replyId`、`state` |
| `subagent.text` | `source`、`type`(TEXT_BLOCK_DELTA)、`delta` |
| `subagent.thinking` | `source`、`type`(THINKING_BLOCK_DELTA)、`delta` |
| `subagent.tool_call` | `source`、`type`(TOOL_CALL_START/TOOL_CALL_END)、`toolName`、`toolCallId`、`toolCallCount` |
| `subagent.tool_result` | `source`、`type`(TOOL_RESULT_END)、`toolCallId` |
| `subagent.require_confirm` | `source`、`type`(REQUIRE_USER_CONFIRM) |

前端目前只认卡片类 CUSTOM（`confirm_card`/`mapping_card`/`approval*`），其余一律降级成「未识别事件 ... (unknown)」的 JSON 卡；
而 `subagent.text` 是**逐 delta** 发送 → 界面被刷屏（用户实测截图）。

## 设计

### 聚合

- 以 `source` 为分组键，在消息树里维护一个 `subagent` 类型的条目：
  - `subagent.lifecycle`：AGENT_START → 新建条目并置为"生成中"；AGENT_END → 置为"已结束"（记录 `state`/耗时若有）。
  - `subagent.text`：把 `delta` **追加**到该条目的正文（不新增条目）。
  - `subagent.thinking`：追加到条目的 thinking 段（弱化显示）。
  - `subagent.tool_call`：START 记录工具名/次数，END 标记该次调用结束。
  - `subagent.tool_result`：标记对应工具调用已返回。
  - `subagent.require_confirm`：在卡片里给一行明确提示（不阻塞主流程）。
- 这族事件**不再**走"未知 CUSTOM → JSON 卡"的降级分支。

### 视觉（简约，与既有「工具调用组」一致）

- **收起态一行**（约 28px 高）：`▸ 子 Agent · <name> · 生成中…/<已结束> · N 次工具调用`，细边框、小图标、次要色文字。
- **展开后**：条目内先渲染子 Agent 正文（Markdown，`prose-sm`），随后是紧凑工具行（`调用 echo ✓`）；
  thinking 用更弱的样式；整体放在浅色块内，不喧宾夺主。
- 多个子 Agent：按 `source` 各成一张卡，互不混淆。
- 流式中：收起那一行有轻微"生成中"指示（不闪屏、不自动展开）。
- 刷新回放：这些事件不落 `chat_message` 正文，因此回放时不会重现卡片（避免历史里堆积框架内部事件）。
  若后续需要回放，再单独设计落库方案（当前非目标）。

## 验收标准

- 触发子 Agent 后，界面**不再出现**「未识别事件」卡片。
- 子 Agent 的输出聚合为**一张**可折叠卡片：收起一行（名称 + 状态 + 工具调用次数），展开可见正文与工具行。
- 同一轮里多个子 Agent → 多张卡，按 `source` 区分。
- 主回答（父 Agent 的流式文本）不受影响，仍照常渲染。
- 该族事件不产生额外数据库写入（不污染 `chat_message`）。
