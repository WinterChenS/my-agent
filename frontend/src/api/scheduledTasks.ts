import { apiFetch } from "./client";

export type ScheduleType = "CRON" | "EVERY_MINUTES" | "ONCE";
export type TaskTarget = "SESSION" | "IM" | "NONE";
export type TaskStatus = "ACTIVE" | "PAUSED" | "ERROR" | "DONE";

export interface ScheduledTaskInfo {
  id: number;
  name: string;
  enabled: boolean;
  agentId: number;
  agentName: string | null;
  scheduleType: ScheduleType;
  cronExpr: string | null;
  intervalMinutes: number | null;
  onceAt: string | null;
  timezone: string;
  /** 人类可读的计划摘要 */
  scheduleText: string;
  prompt: string;
  targetType: TaskTarget;
  /** 目标的人类可读描述（IM · 渠道#2 peer / 会话 #2159 / 只记录） */
  targetText?: string;
  chatSessionId: number | null;
  channelAppId: number | null;
  peerKey: string | null;
  status: TaskStatus;
  misfirePolicy: "COALESCE" | "SKIP";
  nextRunAt: string | null;
  lastRunAt: string | null;
  lastError: string | null;
  runCount: number;
  consecutiveFailures: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface ScheduledTaskPayload {
  name: string;
  agentId: number;
  scheduleType: ScheduleType;
  cronExpr?: string | null;
  intervalMinutes?: number | null;
  onceAt?: string | null;
  timezone?: string;
  prompt: string;
  targetType: TaskTarget;
  /** 目标的人类可读描述（IM · 渠道#2 peer / 会话 #2159 / 只记录） */
  targetText?: string;
  chatSessionId?: number | null;
  channelAppId?: number | null;
  peerKey?: string | null;
  misfirePolicy?: "COALESCE" | "SKIP";
}

export interface ScheduledTaskRunInfo {
  id: number;
  taskId: number;
  nodeId: string;
  triggerType: string;
  status: "RUNNING" | "SUCCESS" | "FAILED" | "ABANDONED";
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
  outputPreview: string | null;
  error: string | null;
}

export interface SchedulePreview {
  scheduleText: string;
  nextRuns: string[];
}

/** 计划预览：保存前先把"接下来 5 次"算出来给用户看，避免表达式写错。 */
export function previewSchedule(payload: {
  scheduleType: ScheduleType;
  cronExpr?: string | null;
  intervalMinutes?: number | null;
  onceAt?: string | null;
  timezone?: string;
  count?: number;
}) {
  return apiFetch<SchedulePreview>("/api/scheduled-tasks/preview", {
    method: "POST",
    body: JSON.stringify({ ...payload, count: payload.count ?? 5 }),
  });
}

/** 已绑定的 IM 接收人（用于把任务结果推到指定的人/群）。 */
export function listChannelPeers(channelId: number) {
  return apiFetch<
    { id: number; peerKey: string; peerKind: string; peerName: string | null }[]
  >(`/api/channels/${channelId}/peers`);
}

/** 绑定一个新的 IM 接收人（用户没主动发过消息时也能推送）。 */
export function bindChannelPeer(channelId: number, peerKey: string, peerName: string) {
  return apiFetch<{ peerKey: string }>(`/api/channels/${channelId}/peers`, {
    method: "POST",
    body: JSON.stringify({ peerKey, peerName }),
  });
}

export function listScheduledTasks(all = false) {
  return apiFetch<ScheduledTaskInfo[]>(`/api/scheduled-tasks?all=${all}`);
}

export function createScheduledTask(payload: ScheduledTaskPayload) {
  return apiFetch<ScheduledTaskInfo>("/api/scheduled-tasks", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function updateScheduledTask(id: number, payload: ScheduledTaskPayload) {
  return apiFetch<ScheduledTaskInfo>(`/api/scheduled-tasks/${id}`, {
    method: "PUT",
    body: JSON.stringify(payload),
  });
}

export function setScheduledTaskStatus(id: number, value: TaskStatus) {
  return apiFetch<ScheduledTaskInfo>(`/api/scheduled-tasks/${id}/status?value=${value}`, {
    method: "POST",
  });
}

export function runScheduledTaskNow(id: number) {
  return apiFetch<void>(`/api/scheduled-tasks/${id}/run`, { method: "POST" });
}

export function deleteScheduledTask(id: number) {
  return apiFetch<void>(`/api/scheduled-tasks/${id}`, { method: "DELETE" });
}

export function listScheduledTaskRuns(id: number, limit = 20) {
  return apiFetch<ScheduledTaskRunInfo[]>(`/api/scheduled-tasks/${id}/runs?limit=${limit}`);
}
