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

/** 列表筛选条件（都可选）。 */
export interface McpListQuery {
  current?: number;
  size?: number;
  /** 名称模糊匹配 */
  keyword?: string;
  status?: "ENABLED" | "DISABLED" | "";
  transport?: McpTransport | "";
}

export function listMcpServers(query: McpListQuery = {}) {
  const params = new URLSearchParams();
  params.set("current", String(query.current ?? 1));
  params.set("size", String(query.size ?? 20));
  if (query.keyword?.trim()) params.set("keyword", query.keyword.trim());
  if (query.status) params.set("status", query.status);
  if (query.transport) params.set("transport", query.transport);
  return apiFetch<PageResult<McpServerInfo>>(`/api/mcp-servers?${params.toString()}`);
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
