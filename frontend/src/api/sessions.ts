import { apiFetch } from "./client";
import type { AgentSummary, ChatMessage, ChatSession, PageResult } from "./types";

export function listSessions() {
  return apiFetch<ChatSession[]>("/api/sessions");
}

export function createSession(agentId: number, threadId?: string) {
  return apiFetch<ChatSession>("/api/sessions", {
    method: "POST",
    body: JSON.stringify({ agentId, threadId }),
  });
}

export function deleteSession(id: number) {
  return apiFetch<void>(`/api/sessions/${id}`, { method: "DELETE" });
}

/** 该会话当前是否在跑、已生成多少文本（Phase 14：刷新/切页回来后接续显示）。 */
export function getRunState(sessionId: number) {
  return apiFetch<{ running: boolean; text: string; runId: string | null; stopped: boolean }>(
    `/api/sessions/${sessionId}/run`,
  );
}

/** 停止该会话正在跑的 run（Phase 12 / D25）。返回是否确实发出了中断。 */
export function interruptSession(id: number) {
  return apiFetch<{ interrupted: boolean }>(`/api/sessions/${id}/interrupt`, {
    method: "POST",
  });
}

export function listMessages(sessionId: number) {
  return apiFetch<ChatMessage[]>(`/api/sessions/${sessionId}/messages`);
}

export function listAgents() {
  return apiFetch<PageResult<AgentSummary>>("/api/agents?current=1&size=100");
}

/** 上下文用量快照（tier1-upgrades ②）。 */
export type ContextUsage = {
  estimatedTokens: number;
  messages: number;
  triggerTokens: number;
  keepTokens: number;
  compacted: boolean;
  summaryPreview: string | null;
};

/** 看该会话上下文用量与阈值。 */
export function getContextUsage(sessionId: number) {
  return apiFetch<ContextUsage>(`/api/sessions/${sessionId}/context`);
}

/** 立刻压缩上下文（走官方压缩能力）。 */
export function compactContext(sessionId: number) {
  return apiFetch<ContextUsage>(`/api/sessions/${sessionId}/context/compact`, { method: "POST" });
}

/** 清空上下文（删框架状态；聊天记录保留）。 */
export function clearContext(sessionId: number) {
  return apiFetch<{ removed: number }>(`/api/sessions/${sessionId}/context/clear`, {
    method: "POST",
  });
}

/** 一轮 run 的历史条目（tier1-upgrades ③，落库副本）。 */
export type RunHistoryItem = {
  id: number;
  runId: string | null;
  status: "RUNNING" | "FINISHED" | "INTERRUPTED" | "ERROR";
  startedAt: string | null;
  endedAt: string | null;
  textSummary?: string | null;
  error?: string | null;
};

/** 该会话最近几轮的状态（后端重启后仍能看到「上一轮跑到哪」）。 */
export function getRunHistory(sessionId: number, limit = 5) {
  return apiFetch<RunHistoryItem[]>(`/api/sessions/${sessionId}/runs?limit=${limit}`);
}

/**
 * 当前正在跑的会话（threadId 列表）。
 *
 * 轻量接口：前端每几秒轮询一次，在会话列表上标记「正在跑」。
 * 为什么要它：Phase 14 之后「断开不中断」是常态 —— 用户切走之后会话还在后台跑，
 * 之前只能等跑完收到站内提示，中间看不出哪条还在跑。
 */
export function listRunningThreads() {
  return apiFetch<{ threadIds: string[] }>("/api/sessions/running").then((d) => d.threadIds);
}

