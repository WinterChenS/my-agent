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
import type {
  AguiEventLike,
  CardKind,
  ChatItem,
  ChatState,
  SubagentItem,
  SubagentStep,
} from "./types";

export const initialChatState: ChatState = { items: [], status: "idle", runId: null };

/** 这些 CUSTOM 事件不产生可视卡片。 */
const NON_VISUAL_CUSTOM = new Set(["token_usage"]);

/** CUSTOM 事件名 → 卡片类型（replay 与实时共用）。 */
export function cardKindOf(name: string): CardKind {
  return CARD_BY_NAME[name] ?? "unknown";
}

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
       * 关键约定：**不在这里插入空气泡**。
       *
       * 踩过的坑（用户实测）：模型有时先发 TEXT_MESSAGE_START（没有内容）、再发思考/工具事件，
       * 最后才补正文。如果 START 就建一条空气泡，它会停在思考块/工具卡**上方**，
       * 后续正文全被追加到那条"上面"的气泡里，看起来顺序完全错乱。
       *
       * 现在的策略：
       *  - 同 messageId 已有条目 → 标记 streaming（沿用）；
       *  - 同一轮还有一条**可继续**的助手气泡（正在流式、且是最后一条）→ 沿用它；
       *  - 否则什么都不建，等第一个非空 CONTENT 到来时在**当前末尾**新建。
       *    这样正文永远出现在它该在的位置（思考/工具之后）。
       */
      const existing = event.messageId
        ? state.items.find((item) => item.kind === "text" && item.id === event.messageId)
        : undefined;
      if (existing) {
        return {
          ...state,
          items: state.items.map((item) =>
            item === existing ? { ...item, streaming: true } : item,
          ),
        };
      }
      const continuable = continuableTextIndex(state.items, state.runId);
      if (continuable !== -1) {
        return {
          ...state,
          items: state.items.map((item, index) =>
            index === continuable && item.kind === "text"
              ? { ...item, id: event.messageId ?? item.id, streaming: true }
              : item,
          ),
        };
      }
      // 不建条目：等内容来（见 upsertText）
      return state;
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

    case "TEXT_MESSAGE_END": {
      const byId = state.items.some(
        (item) => item.kind === "text" && item.id === event.messageId,
      );
      const target = byId
        ? -1
        : continuableTextIndex(state.items, state.runId);
      return {
        ...state,
        items: state.items.map((item, index) => {
          if (item.kind !== "text") {
            return item;
          }
          if (byId ? item.id === event.messageId : index === target) {
            return { ...item, streaming: false };
          }
          return item;
        }),
      };
    }

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
          steps: [],
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
    case "subagent.text": {
      const delta = asString(payload.delta) ?? "";
      current.text += delta;
      current.steps = appendSubagentText(current.steps, "text", delta);
      break;
    }
    case "subagent.thinking": {
      const delta = asString(payload.delta) ?? "";
      current.thinking += delta;
      current.steps = appendSubagentText(current.steps, "thinking", delta);
      break;
    }
    case "subagent.tool_call": {
      if (type === "TOOL_CALL_END") {
        current.toolCalls = markLastToolDone(current.toolCalls);
        current.steps = markLastToolStepDone(current.steps);
      } else {
        current.toolCalls = [
          ...current.toolCalls,
          { name: asString(payload.toolName) ?? "tool", done: false },
        ];
        current.steps = [
          ...current.steps,
          { kind: "tool", name: asString(payload.toolName) ?? "tool", done: false },
        ];
      }
      break;
    }
    case "subagent.tool_result":
      current.toolCalls = markLastToolDone(current.toolCalls);
      current.steps = markLastToolStepDone(current.steps);
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

/** 追加一段正文/思考：连续同类型的 delta 合并到同一个步骤里（否则每个字符一个节点）。 */
function appendSubagentText(
  steps: SubagentStep[],
  kind: "text" | "thinking",
  delta: string,
): SubagentStep[] {
  if (!delta) {
    return steps;
  }
  const last = steps[steps.length - 1];
  if (last && last.kind === kind) {
    return [...steps.slice(0, -1), { kind, text: last.text + delta }];
  }
  return [...steps, { kind, text: delta }];
}

/** 把最后一个还没结束的**工具步骤**标记为已结束。 */
function markLastToolStepDone(steps: SubagentStep[]): SubagentStep[] {
  const next = [...steps];
  for (let i = next.length - 1; i >= 0; i -= 1) {
    const step = next[i];
    if (step.kind === "tool" && !step.done) {
      next[i] = { ...step, done: true };
      break;
    }
  }
  return next;
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

/**
 * 找到"还能继续追加正文"的那条助手气泡：同一轮、正在流式、且是列表里的最后一条。
 *
 * <p>为什么必须是最后一条：一旦思考块/工具卡插到它后面，说明这一段正文已经结束，
 * 后续文字属于**新的一段**，应该在末尾新起气泡（用户实测：否则新内容会跑到上面去）。
 */
function continuableTextIndex(items: ChatItem[], runId?: string | null): number {
  if (!runId || items.length === 0) {
    return -1;
  }
  const last = items.length - 1;
  const tail = items[last];
  if (tail.kind !== "text" || tail.role !== "assistant") {
    return -1;
  }
  if (tail.runId !== runId || !tail.streaming) {
    return -1;
  }
  return last;
}

function upsertText(
  items: ChatItem[],
  messageId: string | undefined,
  delta: string,
  runId?: string | null,
): ChatItem[] {
  const index = items.findIndex((item) => item.kind === "text" && item.id === messageId);
  if (index === -1) {
    // 同一轮还有一条"可继续"的助手气泡（接续先建、或本段还没结束）：并进去，不新建。
    // 注意：只有**最后一条**且在流式中才算可继续——工具/思考插到后面之后就必须新起一条，
    // 否则正文会被追加到上面那条旧气泡里（用户实测的顺序错乱）。
    const sameRun = continuableTextIndex(items, runId);
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

/**
 * 把「已落库但还没决定」的审批补回消息树。
 *
 * <p>为什么需要：刷新/打开历史会话时审批卡本来是**内存里的** SSE 事件，
 * 一旦刷新就消失 —— 而那一轮其实还卡在等审批，用户没法继续（用户要求"回复能衔接上"）。
 * 现在从 {@code /api/approvals} 拉回 PENDING 记录，卡片重新出现，点了就能 resume。
 */
export function appendPendingApprovals(
  state: ChatState,
  records: { interruptId: string; toolName?: string | null; args?: unknown; message?: string | null;
    status: string }[],
): ChatState {
  const pending = records.filter((record) => record.status === "PENDING");
  if (pending.length === 0) {
    return state;
  }
  const existing = new Set(
    state.items
      .filter((item): item is Extract<ChatItem, { kind: "approval" }> => item.kind === "approval")
      .map((item) => item.interruptId),
  );
  const additions = pending
    .filter((record) => !existing.has(record.interruptId))
    .map((record) => ({
      kind: "approval" as const,
      id: `approval-${record.interruptId}`,
      interruptId: record.interruptId,
      toolName: record.toolName ?? "tool",
      args: record.args ?? null,
      message: record.message ?? null,
      status: "pending" as const,
    }));
  if (additions.length === 0) {
    return state;
  }
  return { ...state, items: [...state.items, ...additions] };
}

/** agui_meta.replay 里的一步（后端 ReplayRecorder 折叠过的事件序列）。 */
type ReplayStep = Record<string, unknown>;

function replayStepsOf(meta: Record<string, unknown> | null | undefined): ReplayStep[] | null {
  const raw = meta?.["replay"];
  if (!Array.isArray(raw) || raw.length === 0) {
    return null;
  }
  return raw.filter((step): step is ReplayStep => typeof step === "object" && step !== null);
}

/**
 * replay 步骤 → 消息树 items（历史回放与「运行中接续」共用同一套映射）。
 *
 * <p>{@code tailStreaming=true} 时把最后一个正文标成流式（这一轮还在跑，光标继续闪）。
 */
export function itemsFromReplaySteps(
  steps: ReplayStep[],
  idPrefix: string,
  options: { runId?: string | null; stopped?: boolean; tailStreaming?: boolean } = {},
): ChatItem[] {
  const { runId = null, stopped = false, tailStreaming = false } = options;
  const items: ChatItem[] = [];
  steps.forEach((step, stepIndex) => {
    const key = `${idPrefix}-${stepIndex}`;
    const kind = String(step["kind"] ?? "");
    const isLast = stepIndex === steps.length - 1;
    if (kind === "thinking") {
      items.push({ kind: "thinking", id: key, content: String(step["text"] ?? ""), streaming: false });
      return;
    }
    if (kind === "text") {
      items.push({
        kind: "text",
        id: key,
        role: "assistant",
        content: String(step["text"] ?? ""),
        runId,
        streaming: tailStreaming && isLast,
        ...(stopped && isLast ? { stopped: true } : {}),
      });
      return;
    }
    if (kind === "tool") {
      items.push({
        kind: "tool",
        id: key,
        name: String(step["name"] ?? "tool"),
        args: "",
        result: null,
        state: step["done"] === false ? "ready" : "done",
      });
      return;
    }
    if (kind === "card") {
      const name = String(step["name"] ?? "");
      items.push({
        kind: "card",
        id: key,
        card: cardKindOf(name),
        name,
        data: step["data"] ?? null,
      });
      return;
    }
    if (kind === "subagent") {
      const inner = Array.isArray(step["steps"]) ? (step["steps"] as ReplayStep[]) : [];
      const subSteps: SubagentStep[] = inner.map((item) => {
        if (item["kind"] === "tool") {
          return { kind: "tool", name: String(item["name"] ?? "tool"), done: item["done"] !== false };
        }
        return {
          kind: item["kind"] === "thinking" ? "thinking" : "text",
          text: String(item["text"] ?? ""),
        };
      });
      items.push({
        kind: "subagent",
        id: key,
        source: String(step["source"] ?? "subagent"),
        name: String(step["name"] ?? "subagent"),
        status: step["status"] === "running" ? "running" : "done",
        text: "",
        thinking: "",
        toolCalls: subSteps
          .filter((item): item is { kind: "tool"; name: string; done: boolean } => item.kind === "tool")
          .map((item) => ({ name: item.name, done: item.done })),
        steps: subSteps,
        confirm: typeof step["confirm"] === "string" ? step["confirm"] : null,
      });
    }
  });
  return items;
}

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
    const stopped = message.aguiMeta?.["stopped"] === true;
    const runId = (message.aguiMeta?.["runId"] as string | undefined) ?? null;
    const steps = role === "assistant" ? replayStepsOf(message.aguiMeta) : null;

    if (steps) {
      const rebuilt = itemsFromReplaySteps(steps, `history-${index}`, { runId, stopped });
      const sawText = rebuilt.some((item) => item.kind === "text");
      items.push(...rebuilt);
      // 极端情况：有 replay 但没有正文（只调了工具就被停止）→ 用 content 兜底，别让回复消失
      if (!sawText && message.content) {
        items.push({
          kind: "text",
          id: `history-${index}-content`,
          role: "assistant",
          content: message.content,
          runId,
          streaming: false,
          ...(stopped ? { stopped: true } : {}),
        });
      }
      return;
    }

    if (!message.content) {
      return;
    }
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
