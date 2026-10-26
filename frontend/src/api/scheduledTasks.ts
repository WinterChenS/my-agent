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
