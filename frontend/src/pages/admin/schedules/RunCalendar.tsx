import { useMemo } from "react";
import type { RunDaySummary } from "@/api/scheduledTasks";
import { INTENSITY_CLASS, dayKey, intensityClass } from "@/lib/schedule/calendar";

interface Props {
  /** 当前显示的月份（任意一天即可，内部取月首） */
  month: Date;
  /** 按天聚合的执行统计 */
  summary: RunDaySummary[];
  /** 选中的那一天 */
  selected: string;
  onSelect: (day: string) => void;
  onMonthChange: (next: Date) => void;
}

/**
 * 执行记录日历：格子颜色代表当天执行次数。
 *
 * <p>为什么用日历：定时任务的问题是"到底哪天跑了、哪天没跑、哪天一直失败"，
 * 一列流水看不出来；按天上色一眼就能看出密度和异常（失败多的时候点开看时间轴）。
 */
export default function RunCalendar({ month, summary, selected, onSelect, onMonthChange }: Props) {
  const byDay = useMemo(() => {
    const map = new Map<string, RunDaySummary>();
    summary.forEach((item) => map.set(String(item.day).slice(0, 10), item));
    return map;
  }, [summary]);

  const grid = useMemo(() => {
    const first = new Date(month.getFullYear(), month.getMonth(), 1);
    const daysInMonth = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate();
    const leading = (first.getDay() + 6) % 7; // 周一为一周起点
    const cells: (Date | null)[] = [];
    for (let i = 0; i < leading; i += 1) cells.push(null);
    for (let d = 1; d <= daysInMonth; d += 1) {
      cells.push(new Date(month.getFullYear(), month.getMonth(), d));
    }
    return cells;
  }, [month]);

  const monthLabel = `${month.getFullYear()} 年 ${month.getMonth() + 1} 月`;

  return (
    <div className="rounded-md border p-3" data-testid="run-calendar">
      <div className="mb-2 flex items-center justify-between">
        <div className="text-sm font-medium">{monthLabel}</div>
        <div className="flex gap-1">
          <button
            type="button"
            className="hover:bg-muted rounded px-2 py-0.5 text-xs"
            onClick={() => onMonthChange(new Date(month.getFullYear(), month.getMonth() - 1, 1))}
          >
            上个月
          </button>
          <button
            type="button"
            className="hover:bg-muted rounded px-2 py-0.5 text-xs"
            onClick={() => onMonthChange(new Date(month.getFullYear(), month.getMonth() + 1, 1))}
          >
            下个月
          </button>
        </div>
      </div>

      <div className="text-muted-foreground grid grid-cols-7 gap-1 text-center text-[10px]">
        {["一", "二", "三", "四", "五", "六", "日"].map((d) => (
          <div key={d}>{d}</div>
        ))}
      </div>
      <div className="mt-1 grid grid-cols-7 gap-1">
        {grid.map((date, index) => {
          if (!date) {
            return <div key={`empty-${index}`} />;
          }
          const key = dayKey(date);
          const stats = byDay.get(key);
          const total = stats?.total ?? 0;
          const isSelected = key === selected;
          const hasFailure = (stats?.failed ?? 0) > 0;
          return (
            <button
              key={key}
              type="button"
              data-testid={`calendar-day-${key}`}
              title={
                total === 0
                  ? `${key}：无执行`
                  : `${key}：共 ${total} 次（成功 ${stats?.success ?? 0} / 失败 ${stats?.failed ?? 0} / 取消 ${stats?.cancelled ?? 0}）`
              }
              onClick={() => onSelect(key)}
              className={`relative aspect-square rounded text-xs transition ${intensityClass(total)} ${
                isSelected ? "ring-ring ring-2" : ""
              }`}
            >
              <span>{date.getDate()}</span>
              {total > 0 && (
                <span className="absolute inset-x-0 bottom-0.5 text-[9px] leading-none opacity-80">
                  {total}
                </span>
              )}
              {hasFailure && (
                <span className="bg-destructive absolute top-1 right-1 size-1.5 rounded-full" />
              )}
            </button>
          );
        })}
      </div>

      <div className="text-muted-foreground mt-2 flex items-center gap-2 text-[10px]">
        <span>少</span>
        {[0, 1, 2, 3, 4].map((level) => (
          <span key={level} className={`size-3 rounded ${INTENSITY_CLASS[level]}`} />
        ))}
        <span>多</span>
        <span className="ml-auto flex items-center gap-1">
          <span className="bg-destructive size-1.5 rounded-full" />
          有失败
        </span>
      </div>
    </div>
  );
}
