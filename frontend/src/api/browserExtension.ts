import { apiFetch } from "./client";

/**
 * 本机浏览器扩展（方案 docs/superpowers/specs/2026-10-31-local-browser-extension-design.md）。
 *
 * <p>这里只放**网页侧**需要的接口：安装信息、设备、控制会话。配对与设备令牌是扩展自己调的。
 */

export interface BrowserExtensionConfig {
  mode: string;
  extensionId?: string;
  /** 平台对外地址（后端配置 myagent.platform.public-base-url）；空 = 用当前来源 */
  platformUrl?: string | null;
  /** 内测包绝对下载地址（由后端按配置派生，前端不再拼 IP） */
  extensionPackageUrl?: string | null;
  protocolVersion: number;
  minimumVersion: string;
  chromeStoreUrl?: string | null;
  edgeStoreUrl?: string | null;
  internalPackagePath?: string | null;
  commandTimeoutSeconds: number;
  controlSessionTtlSeconds: number;
}

export interface BrowserDevice {
  id: string;
  displayName?: string | null;
  browser?: string | null;
  extensionVersion?: string | null;
  status: string;
  online: boolean;
  lastSeenAt?: string | null;
}

export type ControlState =
  | "AWAITING_TAB"
  | "AWAITING_ORIGIN_GRANT"
  | "HUMAN_CONTROL"
  | "AGENT_CONTROL"
  | "SUSPENDED"
  | "REVOKED"
  | "EXPIRED";

export interface BrowserExtensionStatus {
  mode: string;
  protocolVersion: number;
  minimumVersion: string;
  devices: BrowserDevice[];
  controlSessionId?: string;
  state?: ControlState;
  leaseVersion?: number;
  origin?: string | null;
  title?: string | null;
  tabHandle?: string | null;
  pageRevision?: number;
  deviceId?: string | null;
  lastError?: string | null;
  expiresAt?: string | null;
  online?: boolean;
}

export interface ControlSession {
  id: string;
  deviceId: string;
  conversationId?: string | null;
  tabHandle?: string | null;
  origin?: string | null;
  title?: string | null;
  state: ControlState;
  leaseVersion: number;
  pageRevision: number;
  lastError?: string | null;
  expiresAt: string;
}

export function fetchExtensionConfig() {
  return apiFetch<BrowserExtensionConfig>("/api/browser/extension/config");
}

export function fetchExtensionStatus(conversationId?: string) {
  const query = conversationId ? `?conversationId=${encodeURIComponent(conversationId)}` : "";
  return apiFetch<BrowserExtensionStatus>(`/api/browser/extension/status${query}`);
}

export function fetchDevices() {
  return apiFetch<BrowserDevice[]>("/api/browser/extension/devices");
}

export function revokeDevice(id: string) {
  return apiFetch<{ revoked: boolean }>(`/api/browser/extension/devices/${id}`, { method: "DELETE" });
}

export function createControlSession(conversationId: string, deviceId?: string) {
  return apiFetch<ControlSession>("/api/browser/extension/control-sessions", {
    method: "POST",
    body: JSON.stringify({ conversationId, deviceId }),
  });
}

export function pauseControlSession(id: string) {
  return apiFetch<ControlSession>(`/api/browser/extension/control-sessions/${id}/pause`, { method: "POST" });
}

export function resumeControlSession(id: string) {
  return apiFetch<ControlSession>(`/api/browser/extension/control-sessions/${id}/resume`, { method: "POST" });
}

export function revokeControlSession(id: string) {
  return apiFetch<{ revoked: boolean }>(`/api/browser/extension/control-sessions/${id}`, {
    method: "DELETE",
  });
}

export interface PairingPreview {
  userCode: string;
  displayName?: string | null;
  browser?: string | null;
  status: string;
  expiresIn: number;
}

export function fetchPairingPreview(userCode: string) {
  return apiFetch<PairingPreview>(`/api/browser/extension/pairings/${encodeURIComponent(userCode)}`);
}

export function approvePairing(userCode: string) {
  return apiFetch<{ approved: boolean }>("/api/browser/extension/pairings/approve", {
    method: "POST",
    body: JSON.stringify({ userCode }),
  });
}

/**
 * 扩展内测包下载地址。
 *
 * <p>优先用后端按 `myagent.platform.public-base-url` 生成的**绝对地址**；
 * 没配时退回相对路径（浏览器会用当前来源）。**前端不拼任何 IP/域名。**
 */
export function extensionPackageUrl(config: BrowserExtensionConfig | null): string | null {
  if (!config) return null;
  return config.extensionPackageUrl || config.internalPackagePath || null;
}
