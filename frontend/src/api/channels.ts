import { apiFetch } from "./client";

/** 渠道类型（当前只接入钉钉，飞书表结构已预留）。 */
export type ChannelType = "DINGTALK" | "FEISHU";

export type ChannelStatus = "STOPPED" | "RUNNING" | "ERROR";

export interface ChannelAppInfo {
  id: number;
  channelType: ChannelType;
  name: string;
  enabled: boolean;
  agentId: number;
  agentName: string | null;
  ownerUserId: number;
  ownerName: string | null;
  status: ChannelStatus;
  lastError: string | null;
  /** 非敏感配置；密钥类字段回显为 ***。 */
  config: Record<string, string>;
  peerCount: number;
  /** 飞书/钉钉 HTTP 模式下的回调地址（Stream 模式为 null）。 */
  webhookPath: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface ChannelAppPayload {
  channelType: ChannelType;
  name: string;
  enabled: boolean;
  agentId: number;
  ownerUserId?: number | null;
  config: Record<string, string>;
}

export interface ChannelPeerInfo {
  id: number;
  peerKey: string;
  peerKind: "DIRECT" | "GROUP";
  peerName: string | null;
  chatSessionId: number | null;
  lastActiveAt: string | null;
}

export function listChannels() {
  return apiFetch<ChannelAppInfo[]>("/api/channels");
}

export function createChannel(payload: ChannelAppPayload) {
  return apiFetch<ChannelAppInfo>("/api/channels", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function updateChannel(id: number, payload: ChannelAppPayload) {
  return apiFetch<ChannelAppInfo>(`/api/channels/${id}`, {
    method: "PUT",
    body: JSON.stringify(payload),
  });
}

export function setChannelEnabled(id: number, value: boolean) {
  return apiFetch<ChannelAppInfo>(`/api/channels/${id}/enabled?value=${value}`, {
    method: "POST",
  });
}

export function restartChannel(id: number) {
  return apiFetch<void>(`/api/channels/${id}/restart`, { method: "POST" });
}

export function deleteChannel(id: number) {
  return apiFetch<void>(`/api/channels/${id}`, { method: "DELETE" });
}

export function listChannelPeers(id: number) {
  return apiFetch<ChannelPeerInfo[]>(`/api/channels/${id}/peers`);
}

/** 修改某个 IM 会话的显示名（「对方」列），不影响推送地址。 */
export function renameChannelPeer(channelId: number, peerId: number, peerName: string) {
  return apiFetch<ChannelPeerInfo>(`/api/channels/${channelId}/peers/${peerId}`, {
    method: "PUT",
    body: JSON.stringify({ peerName }),
  });
}

/** 主动推送（管理页验证连通性时用）。 */
export function pushChannelMessage(id: number, peerKey: string, text: string) {
  return apiFetch<{ sent: number }>(`/api/channels/${id}/push`, {
    method: "POST",
    body: JSON.stringify({ peerKey, text, artifactIds: [] }),
  });
}
