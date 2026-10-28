/**
 * 聊天展示模型：一个 run 归并成的有序「消息树」。
 *
 * <p>与 AG-UI 冻结契约对应（技术方案第 6 节）：文本块、工具调用卡、以及 CUSTOM 事件产生的卡片。
 */

/** 确认卡 data（契约，两端写死）。 */
export interface ConfirmCardData {
  title: string;
  message: string;
  /** 候选答案（Agent 给的，用户直接点选）；为空时后端会给「继续/取消」。 */
  options: { label: string; value: string }[];
  /** 允许用户写候选之外的答案（卡片上出现输入框）。 */
  allowCustom?: boolean;
  requestId: string;
}

/** 映射卡 data（契约，两端写死）。 */
export interface MappingCardData {
  title: string;
  columns: { key: string; label: string }[];
  rows: Record<string, unknown>[];
}

export type CardKind = "approval" | "confirm" | "mapping" | "unknown";

/** AG-UI 的 interrupt（HITL）；字段与官方 Interrupt 类型一致。 */
export interface InterruptLike {
  id: string;
  reason?: string;
  message?: string;
  toolCallId?: string;
  responseSchema?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
}

export interface RunFinishedInterruptOutcomeLike {
  type?: string;
  interrupts?: InterruptLike[];
}

export type ApprovalStatus = "pending" | "approved" | "denied";

/** 子 Agent 的工具调用（收起态只显示次数，展开可见名字与是否返回）。 */
export interface SubagentToolCall {
  name: string;
  done: boolean;
}

/** 子 Agent 事件聚合出来的条目（Phase 12 / D26）。 */
export interface SubagentItem {
  kind: "subagent";
  id: string;
  /** 官方事件里的 source，形如 <threadId>/general-purpose；按它分组 */
  source: string;
  /** 子 Agent 名称（lifecycle 里给的名字，取不到时用 source 末段） */
  name: string;
  status: "running" | "done";
  text: string;
  thinking: string;
  toolCalls: SubagentToolCall[];
  /** 需要用户确认的提示（subagent.require_confirm） */
  confirm: string | null;
}

export type ChatItem =
  | {
      kind: "text";
      id: string;
      role: "user" | "assistant";
      content: string;
      streaming: boolean;
      /**
       * 产出这条消息的那一轮 run id。
       *
       * <p>用它把该轮的产物挂到气泡下方（artifact.runId 与之一致）：
       * 历史回放时来自消息的 aguiMeta.runId，实时流式时来自当前 run。
       */
      runId?: string | null;
      /** 这一轮被用户停止（Phase 12 / D25），气泡上标注「已停止」 */
      stopped?: boolean;
    }
  | { kind: "tool"; id: string; name: string; args: string; result: string | null; state: "args" | "ready" | "done" }
  | { kind: "card"; id: string; card: CardKind; name: string; data: unknown }
  | {
      kind: "approval";
      id: string;
      interruptId: string;
      toolName: string;
      args: unknown;
      message: string | null;
      status: ApprovalStatus;
    }
  | {
      /**
       * 模型的思考过程（AG-UI {@code REASONING_*} 事件）。
       *
       * <p>只在**本轮实时流**里出现，不落库（刷新后就没有了）——思考内容体积大、
       * 且多数场景只是「过程感」，没必要进历史。
       */
      kind: "thinking";
      id: string;
      content: string;
      /** 还在流式接收；收到结束事件后置 false（前端据此从「展开」变「折叠」）。 */
      streaming: boolean;
    }
  | SubagentItem
  | { kind: "error"; id: string; message: string; code: string | null };

export type ChatStatus = "idle" | "running" | "finished" | "error";

export interface ChatState {
  items: ChatItem[];
  status: ChatStatus;
  runId: string | null;
}

/** 只声明 reducer 关心的事件字段，避免与协议实现强耦合。 */
export interface AguiEventLike {
  type: string;
  messageId?: string;
  toolCallId?: string;
  toolCallName?: string;
  delta?: string;
  name?: string;
  value?: unknown;
  result?: unknown;
  content?: unknown;
  message?: string;
  code?: string;
  runId?: string;
  /** RUN_FINISHED 的 outcome；interrupt 类型表示需要人工介入。 */
  outcome?: RunFinishedInterruptOutcomeLike;
}
