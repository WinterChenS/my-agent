import { apiFetch } from "./client";

export type ScheduleType = "CRON" | "EVERY_MINUTES" | "ONCE";
export type TaskTarget = "SESSION" | "IM" | "NONE";
export type TaskStatus = "ACTIVE" | "PAUSED" | "ERROR" | "DONE";
export type RunStatus = "RUNNING" | "SUCCESS" | "FAILED" | "ABANDONED" | "CANCELLED";

/** 日历用：某一天的执行统计（数字越大颜色越深）。 */
export interface RunDaySummary {
  day: string; // YYYY-MM-DD
  total: number;
  success: number;
  failed: number;
  cancelled: number;
  running: number;
}

/** 时间轴用：一条执行记录（跨任务）。 */
export interface RunTimelineItem {
  id: number;
  task_id: number;
  task_name: string;
  trigger_type: string;
  status: RunStatus;
  started_at: string;
  finished_at: string | null;
  duration_ms: number | null;
  output_preview: string | null;
  error: string | null;
  node_id: string;
}

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

/** 某时间窗内按天聚合的执行次数（日历着色）。 */
export function fetchRunSummary(fromIso: string, toIso: string) {
  return apiFetch<RunDaySummary[]>(
    `/api/scheduled-tasks/runs/summary?from=${encodeURIComponent(fromIso)}&to=${encodeURIComponent(toIso)}`,
  );
}

/** 时间轴：时间窗内的执行记录（跨任务，按时间倒序）。 */
export function fetchRunTimeline(fromIso: string, toIso: string, limit = 200) {
  return apiFetch<RunTimelineItem[]>(
    `/api/scheduled-tasks/runs?from=${encodeURIComponent(fromIso)}&to=${encodeURIComponent(toIso)}&limit=${limit}`,
  );
}

/** 取消一次正在跑的执行（后端会 dispose 流并中断 Agent）。 */
export function cancelRun(runId: number) {
  return apiFetch<{ runId: number; cancelled: boolean; taskName: string }>(
    `/api/scheduled-tasks/runs/${runId}/cancel`,
    { method: "POST" },
  );
}

export function listScheduledTaskRuns(id: number, limit = 20) {
  return apiFetch<ScheduledTaskRunInfo[]>(`/api/scheduled-tasks/${id}/runs?limit=${limit}`);
}
