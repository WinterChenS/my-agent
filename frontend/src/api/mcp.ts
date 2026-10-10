import { apiFetch } from "./client";
import type { PageResult } from "./types";

export type McpTransport = "STDIO" | "SSE" | "STREAMABLE_HTTP";

export interface McpServerInfo {
  id: number;
  name: string;
  transport: McpTransport;
  endpointUrl: string | null;
  command: string | null;
  args: string[] | null;
  env: Record<string, string> | null;
  headers: Record<string, string> | null;
  toolCache: McpToolCacheEntry[] | null;
  lastSyncedAt: string | null;
  status: "ENABLED" | "DISABLED";
  createdAt?: string;
  updatedAt?: string;
}

export interface McpToolCacheEntry {
  name: string;
  description?: string | null;
  inputSchema?: Record<string, unknown> | null;
}

export interface McpServerPayload {
  name: string;
  transport: McpTransport;
  endpointUrl?: string | null;
  command?: string | null;
  args?: string[] | null;
  env?: Record<string, string> | null;
  headers?: Record<string, string> | null;
  status?: "ENABLED" | "DISABLED";
}

export const MCP_TRANSPORTS: McpTransport[] = ["STDIO", "SSE", "STREAMABLE_HTTP"];

export function listMcpServers(current = 1, size = 100) {
  return apiFetch<PageResult<McpServerInfo>>(`/api/mcp-servers?current=${current}&size=${size}`);
}

export function createMcpServer(payload: McpServerPayload) {
  return apiFetch<McpServerInfo>("/api/mcp-servers", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function updateMcpServer(id: number, payload: McpServerPayload) {
  return apiFetch<McpServerInfo>(`/api/mcp-servers/${id}`, {
    method: "PUT",
    body: JSON.stringify(payload),
  });
}

export function deleteMcpServer(id: number) {
  return apiFetch<void>(`/api/mcp-servers/${id}`, { method: "DELETE" });
}

/** 拉取工具清单并写入 tool_cache。 */
export function syncMcpTools(id: number) {
  return apiFetch<McpToolCacheEntry[]>(`/api/mcp-servers/${id}/sync-tools`, { method: "POST" });
}
