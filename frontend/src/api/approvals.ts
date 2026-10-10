import { apiFetch } from "./client";
import type { ApprovalStatus } from "@/lib/agui/types";

export interface ApprovalDecisionResponse {
  interruptId: string;
  status: string;
  resumeStatus: "resolved" | "cancelled";
  resumePayload?: Record<string, unknown>;
}

/**
 * 提交审批决定。
 *
 * <p>路径里的 id 是 AG-UI 的 interruptId（前端从 RUN_FINISHED 事件里拿到）。
 * 返回的 resumeStatus 用于发起带 resume 的新一轮 /agui/run。
 */
export function decideApproval(
  interruptId: string,
  decision: "APPROVED" | "DENIED",
  reason?: string,
) {
  return apiFetch<ApprovalDecisionResponse>(
    `/api/approvals/${encodeURIComponent(interruptId)}/decide`,
    { method: "POST", body: JSON.stringify({ decision, reason }) },
  );
}

export interface ApprovalRecord {
  id: number;
  sessionId: number;
  interruptId: string;
  toolName: string | null;
  args: unknown;
  status: ApprovalStatus | string;
  decidedBy: number | null;
  decidedAt: string | null;
  createdAt: string;
}

export function listApprovals(sessionId: number) {
  return apiFetch<ApprovalRecord[]>(`/api/approvals?sessionId=${sessionId}`);
}
