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

export function listMessages(sessionId: number) {
  return apiFetch<ChatMessage[]>(`/api/sessions/${sessionId}/messages`);
}

export function listAgents() {
  return apiFetch<PageResult<AgentSummary>>("/api/agents?current=1&size=100");
}
