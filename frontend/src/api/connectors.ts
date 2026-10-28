import { apiFetch } from "./client";

/**
 * 连接器：让 Agent 使用外部账号（首个类型：邮件）。
 *
 * 归属是**用户**：普通用户只看得到/只能改自己的连接器，管理员可看全部。
 */

/** 一种连接器类型（字段用于动态表单）。 */
export interface ConnectorTypeField {
  key: string;
  label: string;
  secret: boolean;
  hint: string;
  required: boolean;
  placeholder: string;
}

export interface ConnectorTypeInfo {
  type: string;
  displayName: string;
  description: string;
  fields: ConnectorTypeField[];
  requiredKeys: string[];
  secretKeys: string[];
  capabilities: string[];
  inboundSupported: boolean;
  available: boolean;
}

export type ConnectorStatus = "UNKNOWN" | "OK" | "ERROR";
export type InboundMode = "OFF" | "NOTIFY" | "AUTOREPLY";

export interface ConnectorInfo {
  id: number;
  type: string;
  typeName: string;
  name: string;
  alias: string | null;
  /** 账号摘要（邮箱地址）。 */
  account: string | null;
  enabled: boolean;
  status: ConnectorStatus;
  lastError: string | null;
  lastCheckedAt: string | null;
  inboundMode: InboundMode;
  capabilities: string[];
  /** 敏感字段回显为 ***。 */
  config: Record<string, string>;
  agentIds: number[];
  createdAt?: string;
  updatedAt?: string;
}

export interface ConnectorPayload {
  type: string;
  name: string;
  alias?: string | null;
  enabled?: boolean;
  config?: Record<string, string>;
  inboundMode?: InboundMode;
  /** 不传 = 不改绑定；传数组 = 整体替换。 */
  agentIds?: number[];
}

export interface ConnectorTestResult {
  ok: boolean;
  message: string;
  steps: string[];
}

export interface ConnectorActionLog {
  id: number;
  action: string;
  target: string | null;
  subject: string | null;
  success: boolean;
  error: string | null;
  created_at?: string;
  createdAt?: string;
}

export const listConnectorTypes = () => apiFetch<ConnectorTypeInfo[]>("/api/connectors/types");

export const listConnectors = () => apiFetch<ConnectorInfo[]>("/api/connectors");

export const createConnector = (payload: ConnectorPayload) =>
  apiFetch<ConnectorInfo>("/api/connectors", { method: "POST", body: JSON.stringify(payload) });

export const updateConnector = (id: number, payload: ConnectorPayload) =>
  apiFetch<ConnectorInfo>(`/api/connectors/${id}`, { method: "PUT", body: JSON.stringify(payload) });

/**
 * 只切换启用状态（聊天页的小开关用它）。
 *
 * 复用 PUT：`ConnectorService.update` 对「没传的字段」保持原值，
 * 所以只带 `type + enabled` 不会清掉凭据或绑定。
 */
export const setConnectorEnabled = (id: number, enabled: boolean) =>
  apiFetch<ConnectorInfo>(`/api/connectors/${id}`, {
    method: "PUT",
    body: JSON.stringify({ type: "MAIL", enabled }),
  });

export const deleteConnector = (id: number) =>
  apiFetch<void>(`/api/connectors/${id}`, { method: "DELETE" });

export const testConnector = (id: number) =>
  apiFetch<ConnectorTestResult>(`/api/connectors/${id}/test`, { method: "POST" });

/** 新建表单里「测试连接」：用还没保存的配置探测。 */
export const testDraftConnector = (payload: ConnectorPayload) =>
  apiFetch<ConnectorTestResult>("/api/connectors/test", {
    method: "POST",
    body: JSON.stringify(payload),
  });

export const bindConnectorAgents = (id: number, agentIds: number[]) =>
  apiFetch<number[]>(`/api/connectors/${id}/agents`, {
    method: "PUT",
    body: JSON.stringify({ agentIds }),
  });

export const listConnectorActions = (id: number) =>
  apiFetch<ConnectorActionLog[]>(`/api/connectors/${id}/actions`);
