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

    case "TEXT_MESSAGE_START": {
      /*
       * 同一轮（同 runId）已经有一条助手气泡时**复用它**，不要新建。
       *
       * 为什么：这一轮的文字可能同时来自两处 —— 实时 SSE 流，以及"接续正在跑的 run"
       * （切回会话/刷新后按 /run 轮询把已生成的文本补上）。两者先后不定，
       * 若各建一条，界面上就会出现**两个气泡装着同一轮的内容**（实测：全量里表现为
       * 顺序断言多出一条 assistant 气泡）。归并键就是 runId。
       */
      const sameRun = state.runId
        ? state.items.find(
            (item) => item.kind === "text" && item.role === "assistant" && item.runId === state.runId,
          )
        : undefined;
      if (sameRun) {
        return {
          ...state,
          items: state.items.map((item) =>
            item === sameRun
              ? { ...item, id: event.messageId ?? item.id, streaming: true }
              : item,
          ),
        };
      }
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
    }

    /*
     * 思考过程（REASONING_*，官方 enableReasoning 打开后才会下发）。
     *
     * 官方/客户端约定：
     *   REASONING_START          —— 本轮开始思考（无内容）
     *   REASONING_MESSAGE_START  —— 开始一条思考消息（带 messageId）
     *   REASONING_MESSAGE_CONTENT—— 增量 delta（逐 token）
     *   REASONING_MESSAGE_END    —— 该条思考结束
     *   REASONING_END            —— 思考阶段整体结束
     *
     * 一条回复里可能有**多段**思考（每次工具调用前后各一段），按 messageId 各成一条，
     * 顺序与到达顺序一致，这样思考块会自然落在它对应的文本/工具卡之前。
     */
    case "REASONING_START":
      // START 只是「开始思考了」的信号，没有内容：**不建条目**，
      // 否则会和紧接着的 MESSAGE_START 各建一条，界面上出现两个空的思考块。
      return state;

    case "REASONING_MESSAGE_START":
      return { ...state, items: upsertThinking(state.items, event.messageId, "", true) };

    case "REASONING_MESSAGE_CONTENT":
    case "REASONING_MESSAGE_CHUNK":
      return {
        ...state,
        items: upsertThinking(state.items, event.messageId, event.delta ?? "", true),
      };

    case "REASONING_MESSAGE_END":
      return {
        ...state,
        items: state.items.map((item) =>
          item.kind === "thinking" && item.id === thinkingId(event.messageId)
            ? { ...item, streaming: false }
            : item,
        ),
      };

    case "REASONING_END":
      // 兜底：结束阶段把所有还在 streaming 的思考块收掉（个别服务端不发 MESSAGE_END）
      return {
        ...state,
        items: state.items.map((item) =>
          item.kind === "thinking" && item.streaming ? { ...item, streaming: false } : item,
        ),
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

/** 思考块的稳定 id：同一条思考消息多次增量都落到同一个条目。 */
function thinkingId(messageId: string | undefined): string {
  return `thinking-${messageId ?? "run"}`;
}

/**
 * 把一段思考增量合并进条目列表：没有就新建（按到达顺序 push），有就追加。
 *
 * <p>为什么新建时**不**插到最前面：思考发生在其对应的文本/工具卡之前，按到达顺序 push
 * 才能保证「思考 → 文本 → 工具 → 思考 → 文本」这样的真实顺序不乱。
 */
function upsertThinking(
  items: ChatItem[],
  messageId: string | undefined,
  delta: string,
  streaming: boolean,
): ChatItem[] {
  const id = thinkingId(messageId);
  const index = items.findIndex((item) => item.kind === "thinking" && item.id === id);
  if (index < 0) {
    return [
      ...items,
      { kind: "thinking", id, content: delta, streaming },
    ];
  }
  const current = items[index] as Extract<ChatItem, { kind: "thinking" }>;
  const next: ChatItem = {
    ...current,
    content: current.content + delta,
    streaming: current.streaming || streaming,
  };
  return items.map((item, i) => (i === index ? next : item));
}

function applyRunFinished(state: ChatState, event: AguiEventLike): ChatState {
  const interrupts = event.outcome?.interrupts ?? [];
  // 结束就把思考块收掉：否则断流/异常时它会永远显示「思考中…」
  const settled = state.items.map((item) =>
    item.kind === "thinking" && item.streaming ? { ...item, streaming: false } : item,
  );
  if (interrupts.length === 0) {
    return { ...state, status: "finished", items: settled };
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
  return { ...state, status: "finished", items: [...settled, ...cards] };
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
    // 同一轮已有气泡（可能来自"接续"）：把增量并进去，**不新建**（理由见 TEXT_MESSAGE_START）
    const sameRun =
      runId == null
        ? -1
        : items.findIndex(
            (item) => item.kind === "text" && item.role === "assistant" && item.runId === runId,
          );
    if (sameRun !== -1) {
      return items.map((item, i) =>
        i === sameRun && item.kind === "text"
          ? { ...item, id: messageId ?? item.id, content: item.content + delta, streaming: true }
          : item,
      );
    }
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
