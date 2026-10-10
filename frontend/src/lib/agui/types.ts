/**
 * 聊天展示模型：一个 run 归并成的有序「消息树」。
 *
 * <p>与 AG-UI 冻结契约对应（技术方案第 6 节）：文本块、工具调用卡、以及 CUSTOM 事件产生的卡片。
 */

/** 确认卡 data（契约，两端写死）。 */
export interface ConfirmCardData {
  title: string;
  message: string;
  options: { label: string; value: string }[];
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

export type ChatItem =
  | { kind: "text"; id: string; role: "user" | "assistant"; content: string; streaming: boolean }
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
