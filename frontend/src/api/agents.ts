import { apiFetch } from "./client";
import type { AgentSummary, PageResult } from "./types";

export type PermissionMode = "ALWAYS_ALLOW" | "ALWAYS_DENY" | "REQUIRE_APPROVAL";

export interface AgentDetail {
  id: number;
  name: string;
  agentId: string;
  sysPrompt: string | null;
  modelId: number | null;
  temperature: number | null;
  maxTokens: number | null;
  permissionMode: PermissionMode;
  maxIterations: number | null;
  status: "ENABLED" | "DISABLED";
  skillIds: number[];
  mcpServerIds: number[];
  createdAt?: string;
  updatedAt?: string;
}

export interface AgentPayload {
  name: string;
  agentId: string;
  sysPrompt?: string | null;
  modelId: number;
  temperature?: number | null;
  maxTokens?: number | null;
  permissionMode: PermissionMode;
  maxIterations?: number | null;
  status?: "ENABLED" | "DISABLED";
  skillIds: number[];
  mcpServerIds: number[];
}

export const PERMISSION_MODES: PermissionMode[] = [
  "ALWAYS_ALLOW",
  "REQUIRE_APPROVAL",
  "ALWAYS_DENY",
];

export function listAgentDetails(current = 1, size = 200) {
  return apiFetch<PageResult<AgentDetail>>(`/api/agents?current=${current}&size=${size}`);
}

export function getAgent(id: number) {
  return apiFetch<AgentDetail>(`/api/agents/${id}`);
}

export function createAgent(payload: AgentPayload) {
  return apiFetch<AgentDetail>("/api/agents", { method: "POST", body: JSON.stringify(payload) });
}

export function updateAgent(id: number, payload: AgentPayload) {
  return apiFetch<AgentDetail>(`/api/agents/${id}`, { method: "PUT", body: JSON.stringify(payload) });
}

export function deleteAgent(id: number) {
  return apiFetch<void>(`/api/agents/${id}`, { method: "DELETE" });
}

export type { AgentSummary };
