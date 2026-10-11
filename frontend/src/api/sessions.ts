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
