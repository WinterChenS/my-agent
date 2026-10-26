import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import ScheduleEditor from "@/pages/admin/schedules/ScheduleEditor";

const previewSchedule = vi.fn();

vi.mock("@/api/scheduledTasks", () => ({
  previewSchedule: (...args: unknown[]) => previewSchedule(...args),
}));

function renderEditor(overrides: Partial<Parameters<typeof ScheduleEditor>[0]> = {}) {
  const onChange = vi.fn();
  const props = {
    scheduleType: "CRON" as const,
    cronExpr: "0 9 * * *",
    intervalMinutes: "60",
    onceAtLocal: "",
    timezone: "Asia/Shanghai",
    onChange,
    ...overrides,
  };
  render(<ScheduleEditor {...props} />);
  return { onChange };
}

/**
 * 计划配置器：预设按钮、表单拼 cron、实时预览。
 *
 * <p>为什么单独测：用户要求"可视化配置且配置要生效"，这里的每个按钮都必须真的改到 cron 表达式，
 * 而预览必须用后端同一套计算（不然界面显示的时间会和实际触发不一致）。
 */
describe("ScheduleEditor：可视化配置 cron", () => {
  beforeEach(() => {
    previewSchedule.mockReset();
    previewSchedule.mockResolvedValue({
      scheduleText: "cron: 0 9 * * *（Asia/Shanghai）",
      nextRuns: ["2026-10-27T09:00:00+08:00", "2026-10-28T09:00:00+08:00"],
    });
  });

  it("常用预设按钮都在，点一下会改表达式", () => {
    const { onChange } = renderEditor();

    expect(screen.getByText("每天 9:00")).toBeTruthy();
    expect(screen.getByText("工作日 9:00")).toBeTruthy();
    expect(screen.getByText("每月 1 号 9:00")).toBeTruthy();

    fireEvent.click(screen.getByText("工作日 9:00"));
    expect(onChange).toHaveBeenCalledWith({ cronExpr: "0 9 * * 1-5" });
  });

  it("改小时只替换对应那一段，其余不动", () => {
    const { onChange } = renderEditor({ cronExpr: "0 9 * * *" });

    const hour = screen.getByDisplayValue("9");
    fireEvent.change(hour, { target: { value: "18" } });

    expect(onChange).toHaveBeenCalledWith({ cronExpr: "0 18 * * *" });
  });

  it("星期选择器覆盖周几那一段", () => {
    const { onChange } = renderEditor({ cronExpr: "0 9 * * *" });

    const weekday = screen.getByDisplayValue("每天");
    fireEvent.change(weekday, { target: { value: "1-5" } });

    expect(onChange).toHaveBeenCalledWith({ cronExpr: "0 9 * * 1-5" });
  });

  it("实时预览会显示接下来几次执行时间", async () => {
    renderEditor();

    await waitFor(() => {
      expect(previewSchedule).toHaveBeenCalledWith(
        expect.objectContaining({ scheduleType: "CRON", cronExpr: "0 9 * * *", count: 5 }),
      );
    });
    await waitFor(() => {
      // 预览区会显示后端返回的摘要（同一套计算）与序号，说明"所见即所得"生效
      expect(screen.getByText(/cron: 0 9/)).toBeTruthy();
      expect(screen.getByText("#1")).toBeTruthy();
      expect(screen.getByText("#2")).toBeTruthy();
    });
  });

  it("表达式不合法时直接提示，而不是静默", async () => {
    previewSchedule.mockRejectedValue(new Error("Invalid value for HourOfDay"));
    renderEditor({ cronExpr: "0 99 * * *" });

    await waitFor(() => {
      expect(screen.getByText(/计划不合法/)).toBeTruthy();
    });
  });

  it("固定间隔模式给出常用间隔与自定义输入", () => {
    const { onChange } = renderEditor({ scheduleType: "EVERY_MINUTES" });

    fireEvent.click(screen.getByText("每 15 分钟"));
    expect(onChange).toHaveBeenCalledWith({ intervalMinutes: "15" });
  });

  it("一次性模式支持快捷时间（N 分钟后）", () => {
    const { onChange } = renderEditor({ scheduleType: "ONCE" });

    fireEvent.click(screen.getByText("30 分钟后"));
    const patch = onChange.mock.calls[0][0] as { onceAtLocal: string };
    expect(patch.onceAtLocal).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
  });
});
