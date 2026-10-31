import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import SchedulesPage from "./SchedulesPage";

const fetchRunSummary = vi.fn();
const fetchRunTimeline = vi.fn();
const listScheduledTasks = vi.fn();
const runScheduledTaskNow = vi.fn();
const listScheduledTaskRuns = vi.fn();

vi.mock("@/api/scheduledTasks", () => ({
  fetchRunSummary: (...a: unknown[]) => fetchRunSummary(...a),
  fetchRunTimeline: (...a: unknown[]) => fetchRunTimeline(...a),
  listScheduledTasks: (...a: unknown[]) => listScheduledTasks(...a),
  runScheduledTaskNow: (...a: unknown[]) => runScheduledTaskNow(...a),
  listScheduledTaskRuns: (...a: unknown[]) => listScheduledTaskRuns(...a),
  createScheduledTask: vi.fn(),
  updateScheduledTask: vi.fn(),
  deleteScheduledTask: vi.fn(),
  previewSchedule: vi.fn(),
  pauseScheduledTask: vi.fn(),
  resumeScheduledTask: vi.fn(),
}));
vi.mock("@/api/agents", () => ({ listAgentDetails: vi.fn().mockResolvedValue({ records: [] }) }));
vi.mock("@/api/channels", () => ({ listChannels: vi.fn().mockResolvedValue([]) }));
vi.mock("@/api/sessions", () => ({ listSessions: vi.fn().mockResolvedValue({ records: [], total: 0, current: 1, size: 20 }) }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }));

const TASK = {
  id: 7,
  name: "科技早报",
  agentId: 37,
  enabled: true,
  status: "ACTIVE",
  scheduleType: "CRON" as const,
  cronExpr: "0 9 * * *",
  intervalMinutes: null,
  onceAt: null,
  timezone: "Asia/Shanghai",
  prompt: "生成早报",
  targetType: "SESSION" as const,
  chatSessionId: 1,
  channelAppId: null,
  peerKey: null,
  targetText: "当前会话",
  nextRunAt: null,
  lastRunAt: null,
  consecutiveFailures: 0,
  lastError: null,
};

/**
 * 执行记录必须"自己会动"（用户要求：默认 5s 轮询；点跑/重跑后延迟 2s 刷新）。
 *
 * <p>为什么要用 fake timers 测：这两条都是**时间行为**，肉眼看代码看不出来；
 * 之前点完重跑是"自己 sleep 1.5s + 页面再刷"，叠加成 3.5s 才动，用户体感就是"点了没反应"。
 */
describe("定时任务页：执行记录轮询与操作后刷新", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    fetchRunSummary.mockReset().mockResolvedValue([]);
    fetchRunTimeline.mockReset().mockResolvedValue([]);
    listScheduledTasks.mockReset().mockResolvedValue([TASK]);
    runScheduledTaskNow.mockReset().mockResolvedValue({ runId: 1 });
    listScheduledTaskRuns.mockReset().mockResolvedValue([]);
  });

  it("每 5s 自动刷新一次执行记录（silent，不闪 loading）", async () => {
    render(<SchedulesPage />);
    await waitFor(() => expect(fetchRunTimeline).toHaveBeenCalledTimes(1));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(fetchRunTimeline).toHaveBeenCalledTimes(2);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(fetchRunTimeline).toHaveBeenCalledTimes(3);
  });

  it("点「立即执行」后，延迟 2s 才刷新执行记录", async () => {
    render(<SchedulesPage />);
    await waitFor(() => expect(screen.getByText("科技早报")).toBeInTheDocument());
    await waitFor(() => expect(fetchRunTimeline).toHaveBeenCalledTimes(1));

    const runButton = await screen.findByTitle("立即执行");
    await act(async () => {
      runButton.click();
    });
    await waitFor(() => expect(runScheduledTaskNow).toHaveBeenCalledWith(7));

    // 1s 时还没刷（避免"投递了但记录还没建出来"的空刷）
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    const callsAt1s = fetchRunTimeline.mock.calls.length;

    // 再走 1s（累计 2s）→ 应该刷了
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1100);
    });
    expect(fetchRunTimeline.mock.calls.length).toBeGreaterThan(callsAt1s);
  });
});
