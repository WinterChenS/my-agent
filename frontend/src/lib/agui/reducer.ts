/**
 * AG-UI 事件流 → 消息树 reducer（纯函数，便于单测）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>文本块按 {@code messageId} 归并，逐字追加
 *   <li>工具调用按 {@code toolCallId} 归并，参数与结果分别累积
 *   <li>CUSTOM 事件按 {@code name} 映射为卡片；未知类型降级为 JSON 卡而不是崩溃
 *   <li>非展示型 CUSTOM（如 token_usage）直接忽略
 * </ul>
 */
import type { AguiEventLike, CardKind, ChatItem, ChatState, SubagentItem } from "./types";

export const initialChatState: ChatState = { items: [], status: "idle", runId: null };

/** 这些 CUSTOM 事件不产生可视卡片。 */
const NON_VISUAL_CUSTOM = new Set(["token_usage"]);

const CARD_BY_NAME: Record<string, CardKind> = {
  confirm_card: "confirm",
  mapping_card: "mapping",
  approval: "approval",
  approval_card: "approval",
  permission_confirm: "approval",
};

export function reduceEvent(state: ChatState, event: AguiEventLike): ChatState {
  switch (event.type) {
    case "RUN_STARTED":
      // 新的一轮：**只改运行状态，不清空消息树**。
      // 一次会话的 items 是“跨轮累积”的；之前这里 filter 掉非审批卡项，
      // 导致用户一发消息就把历史对话和自己刚发的气泡全抹掉（用户实测回归）。
      return {
        ...state,
        status: "running",
        runId: event.runId ?? state.runId,
      };

    case "TEXT_MESSAGE_START":
      return {
        ...state,
        items: [
          ...state.items,
          {
            kind: "text",
            id: event.messageId ?? `assistant-${state.items.length}`,
            role: "assistant",
            content: "",
            streaming: true,
            runId: state.runId,
          },
        ],
      };

    case "TEXT_MESSAGE_CONTENT":
      return {
        ...state,
        items: upsertText(state.items, event.messageId, event.delta ?? "", state.runId),
      };

    case "TEXT_MESSAGE_END":
      return {
        ...state,
        items: state.items.map((item) =>
          item.kind === "text" && item.id === event.messageId
            ? { ...item, streaming: false }
            : item,
        ),
      };

    case "TOOL_CALL_START":
      return {
        ...state,
        items: [
          ...state.items,
          {
            kind: "tool",
            id: event.toolCallId ?? `tool-${state.items.length}`,
            name: event.toolCallName ?? "unknown",
            args: "",
            result: null,
            state: "args",
          },
        ],
      };

    case "TOOL_CALL_ARGS":
      return {
        ...state,
        items: state.items.map((item) =>
          item.kind === "tool" && item.id === event.toolCallId
            ? { ...item, args: item.args + (event.delta ?? "") }
            : item,
        ),
      };

    case "TOOL_CALL_END":
      return {
        ...state,
        items: state.items.map((item) =>
          item.kind === "tool" && item.id === event.toolCallId
            ? { ...item, state: "ready" }
            : item,
        ),
      };

    case "TOOL_CALL_RESULT":
      return {
        ...state,
        items: state.items.map((item) =>
          item.kind === "tool" && item.id === event.toolCallId
            ? { ...item, result: stringify(event.result ?? event.content), state: "done" }
            : item,
        ),
      };

    case "CUSTOM":
      return applyCustom(state, event);

    case "RUN_ERROR":
      return {
        ...state,
        status: "error",
        items: [
          ...state.items,
          {
            kind: "error",
            id: `error-${state.items.length}`,
            message: event.message ?? "运行失败",
            code: event.code ?? null,
          },
        ],
      };

    case "RUN_FINISHED":
      return applyRunFinished(state, event);

    default:
      // 未声明的事件（含 STATE_SNAPSHOT / STATE_DELTA / REASONING_*）不改变展示树
      return state;
  }
}

function applyRunFinished(state: ChatState, event: AguiEventLike): ChatState {
  const interrupts = event.outcome?.interrupts ?? [];
  if (interrupts.length === 0) {
    return { ...state, status: "finished" };
  }
  // 需要人工审批：把每个 interrupt 变成一张审批卡（工具名/入参来自 metadata）
  const cards: ChatItem[] = interrupts.map((interrupt, index) => ({
    kind: "approval" as const,
    id: `approval-${interrupt.id || index}`,
    interruptId: interrupt.id,
    toolName: asString(interrupt.metadata?.toolName) ?? interrupt.toolCallId ?? "未知工具",
    args: interrupt.metadata?.toolInput ?? interrupt.metadata?.toolContent ?? null,
    message: interrupt.message ?? null,
    status: "pending" as const,
  }));
  return { ...state, status: "finished", items: [...state.items, ...cards] };
}

/** 审批卡状态更新（决策后由页面调用）。 */
/**
 * 子 Agent 事件聚合（Phase 12 / D26）。
 *
 * <p>官方事件族：`subagent.lifecycle`（AGENT_START/AGENT_END）、`subagent.text`（TEXT_BLOCK_DELTA）、
 * `subagent.thinking`、`subagent.tool_call`（TOOL_CALL_START/END）、`subagent.tool_result`、`subagent.require_confirm`。
 * `text`/`thinking` 是**逐 delta** 的，必须追加到同一条目，否则会像以前那样刷屏。
 */
function applySubagent(state: ChatState, name: string, value: unknown): ChatState {
  const payload = (value ?? {}) as Record<string, unknown>;
  const source = asString(payload.source) ?? "subagent";
  const type = asString(payload.type) ?? "";
  const index = state.items.findIndex(
    (item) => item.kind === "subagent" && item.source === source,
  );

  const current: SubagentItem =
    index >= 0
      ? { ...(state.items[index] as SubagentItem) }
      : {
          kind: "subagent",
          id: `subagent-${source}`,
          source,
          name: source.split("/").pop() || "subagent",
          status: "running",
          text: "",
          thinking: "",
          toolCalls: [],
          confirm: null,
        };

  switch (name) {
    case "subagent.lifecycle": {
      const label = asString(payload.name);
      if (label) {
        current.name = label;
      }
      if (type === "AGENT_END") {
        current.status = "done";
      } else if (type === "AGENT_START") {
        current.status = "running";
      }
      break;
    }
    case "subagent.text":
      current.text += asString(payload.delta) ?? "";
      break;
    case "subagent.thinking":
      current.thinking += asString(payload.delta) ?? "";
      break;
    case "subagent.tool_call": {
      if (type === "TOOL_CALL_END") {
        current.toolCalls = markLastToolDone(current.toolCalls);
      } else {
        current.toolCalls = [
          ...current.toolCalls,
          { name: asString(payload.toolName) ?? "tool", done: false },
        ];
      }
      break;
    }
    case "subagent.tool_result":
      current.toolCalls = markLastToolDone(current.toolCalls);
      break;
    case "subagent.require_confirm":
      current.confirm = asString(payload.message) ?? "子 Agent 请求确认";
      break;
    default:
      break;
  }

  const items =
    index >= 0
      ? state.items.map((item, i) => (i === index ? current : item))
      : [...state.items, current];
  return { ...state, items };
}

/** 把最后一个还没结束的工具调用标记为已结束。 */
function markLastToolDone(toolCalls: SubagentItem["toolCalls"]): SubagentItem["toolCalls"] {
  const next = [...toolCalls];
  for (let i = next.length - 1; i >= 0; i -= 1) {
    if (!next[i].done) {
      next[i] = { ...next[i], done: true };
      break;
    }
  }
  return next;
}

export function markApproval(
  state: ChatState,
  interruptId: string,
  status: "approved" | "denied",
): ChatState {
  return {
    ...state,
    items: state.items.map((item) =>
      item.kind === "approval" && item.interruptId === interruptId ? { ...item, status } : item,
    ),
  };
}

function asString(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null;
}

function applyCustom(state: ChatState, event: AguiEventLike): ChatState {
  const name = event.name ?? "";
  if (NON_VISUAL_CUSTOM.has(name)) {
    return state;
  }
  // Phase 12 / D26：官方 SubagentEventConverter 发出的一族事件，按 source 聚合成一张卡片
  if (name.startsWith("subagent.")) {
    return applySubagent(state, name, event.value);
  }
  const card: CardKind = CARD_BY_NAME[name] ?? "unknown";
  return {
    ...state,
    items: [
      ...state.items,
      { kind: "card", id: `card-${state.items.length}`, card, name, data: event.value },
    ],
  };
}

function upsertText(
  items: ChatItem[],
  messageId: string | undefined,
  delta: string,
  runId?: string | null,
): ChatItem[] {
  const index = items.findIndex((item) => item.kind === "text" && item.id === messageId);
  if (index === -1) {
    // 没有 START 就来了 CONTENT：补一个文本块，保证内容不丢
    return [
      ...items,
      {
        kind: "text",
        id: messageId ?? `assistant-${items.length}`,
        role: "assistant",
        content: delta,
        streaming: true,
        runId,
      },
    ];
  }
  return items.map((item, i) =>
    i === index && item.kind === "text" ? { ...item, content: item.content + delta } : item,
  );
}

function stringify(value: unknown): string {
  if (typeof value === "string") {
    return value;
  }
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

/** 历史消息 → 消息树（回放）。 */
export function replayMessages(
  messages: {
    role: string;
    content: string | null;
    aguiMeta?: Record<string, unknown> | null;
  }[],
): ChatState {
  const items: ChatItem[] = [];
  messages.forEach((message, index) => {
    const role = message.role === "USER" ? "user" : "assistant";
    if (!message.content) {
      return;
    }
    const stopped = message.aguiMeta?.["stopped"] === true;
    const runId = (message.aguiMeta?.["runId"] as string | undefined) ?? null;
    items.push({
      kind: "text",
      id: `history-${index}`,
      role,
      content: message.content,
      runId,
      streaming: false,
      // Phase 12 / D25：被停止的那一轮刷新后仍标注「已停止」
      ...(stopped ? { stopped: true } : {}),
    });
  });
  return { items, status: "idle", runId: null };
}
