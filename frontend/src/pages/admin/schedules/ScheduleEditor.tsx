import { useEffect, useMemo, useState } from "react";
import { Clock, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { previewSchedule, type ScheduleType } from "@/api/scheduledTasks";

/** 常用计划预设（点一下就把表达式填好，也能看懂各自含义）。 */
const PRESETS: { label: string; type: ScheduleType; cron?: string; interval?: number }[] = [
  { label: "每天 9:00", type: "CRON", cron: "0 9 * * *" },
  { label: "每天 18:30", type: "CRON", cron: "30 18 * * *" },
  { label: "工作日 9:00", type: "CRON", cron: "0 9 * * 1-5" },
  { label: "每周一 8:30", type: "CRON", cron: "30 8 * * 1" },
  { label: "每月 1 号 9:00", type: "CRON", cron: "0 9 1 * *" },
  { label: "每小时整点", type: "CRON", cron: "0 * * * *" },
  { label: "每 15 分钟", type: "EVERY_MINUTES", interval: 15 },
  { label: "每 1 小时", type: "EVERY_MINUTES", interval: 60 },
];

const WEEKDAYS = [
  { value: "*", label: "每天" },
  { value: "1-5", label: "工作日（周一至周五）" },
  { value: "0,6", label: "周末" },
  { value: "1", label: "周一" },
  { value: "2", label: "周二" },
  { value: "3", label: "周三" },
  { value: "4", label: "周四" },
  { value: "5", label: "周五" },
  { value: "6", label: "周六" },
  { value: "0", label: "周日" },
];

interface Props {
  scheduleType: ScheduleType;
  cronExpr: string;
  intervalMinutes: string;
  onceAtLocal: string;
  timezone: string;
  onChange: (patch: {
    scheduleType?: ScheduleType;
    cronExpr?: string;
    intervalMinutes?: string;
    onceAtLocal?: string;
  }) => void;
}

/** 把 cron 拆成 分/时/日/月/周 便于用表单拼装（6 段时忽略秒）。 */
function splitCron(expr: string): string[] {
  const parts = expr.trim().split(/\s+/);
  if (parts.length === 6) return parts.slice(1);
  if (parts.length === 5) return parts;
  return ["0", "9", "*", "*", "*"];
}

/**
 * 计划配置器（可视化）：预设 + 表单拼 cron + 高级手写 + 「接下来 5 次」实时预览。
 *
 * <p>预览直接调后端同一套 {@code ScheduleMath}，所见即所得：
 * 界面上看到的时间就是真正会触发的时间。
 */
export default function ScheduleEditor({
  scheduleType,
  cronExpr,
  intervalMinutes,
  onceAtLocal,
  timezone,
  onChange,
}: Props) {
  const [advanced, setAdvanced] = useState(false);
  const [preview, setPreview] = useState<{ scheduleText: string; nextRuns: string[] } | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [peak, setPeak] = useState(false);

  const [minute, hour, day, month, weekday] = useMemo(() => splitCron(cronExpr), [cronExpr]);

  function patchCron(index: number, value: string) {
    const parts = splitCron(cronExpr);
    parts[index] = value;
    onChange({ cronExpr: parts.join(" ") });
  }

  // 实时预览：类型/表达式/间隔/时间变了就重算
  useEffect(() => {
    let alive = true;
    const timer = setTimeout(() => {
      const onceAt =
        scheduleType === "ONCE" && onceAtLocal ? new Date(onceAtLocal).toISOString() : null;
      previewSchedule({
        scheduleType,
        cronExpr: scheduleType === "CRON" ? cronExpr : null,
        intervalMinutes: scheduleType === "EVERY_MINUTES" ? Number(intervalMinutes || 60) : null,
        onceAt,
        timezone,
        count: 5,
      })
        .then((result) => {
          if (!alive) return;
          setPreview(result);
          setPreviewError(null);
        })
        .catch((err) => {
          if (!alive) return;
          setPreview(null);
          setPreviewError(err instanceof Error ? err.message : "计划不合法");
        });
    }, 300);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [scheduleType, cronExpr, intervalMinutes, onceAtLocal, timezone]);

  return (
    <div className="space-y-2 rounded-md border p-3">
      <div className="flex items-center gap-2 text-sm font-medium">
        <Clock className="h-4 w-4" />
        执行计划
      </div>

      {scheduleType === "CRON" && (
        <>
          <div className="flex flex-wrap gap-1">
            {PRESETS.filter((p) => p.type === "CRON").map((preset) => (
              <Button
                key={preset.label}
                type="button"
                size="sm"
                variant={cronExpr.trim() === preset.cron ? "default" : "outline"}
                onClick={() => onChange({ cronExpr: preset.cron ?? "* * * * *" })}
              >
                {preset.label}
              </Button>
            ))}
          </div>

          {!advanced && (
            <div className="grid grid-cols-2 gap-2">
              <label className="block text-xs">
                小时
                <Input
                  className="mt-1 h-8"
                  value={hour}
                  onChange={(e) => patchCron(1, e.target.value)}
                  placeholder="9"
                />
              </label>
              <label className="block text-xs">
                分钟
                <Input
                  className="mt-1 h-8"
                  value={minute}
                  onChange={(e) => patchCron(0, e.target.value)}
                  placeholder="0"
                />
              </label>
              <label className="block text-xs">
                几号（* = 每天）
                <Input
                  className="mt-1 h-8"
                  value={day}
                  onChange={(e) => patchCron(2, e.target.value)}
                  placeholder="*"
                />
              </label>
              <label className="block text-xs">
                星期
                <select
                  className="border-input bg-background mt-1 h-8 w-full rounded-md border px-2 text-xs"
                  value={weekday}
                  onChange={(e) => patchCron(4, e.target.value)}
                >
                  {WEEKDAYS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                  {!WEEKDAYS.some((o) => o.value === weekday) && (
                    <option value={weekday}>自定义（{weekday}）</option>
                  )}
                </select>
              </label>
              <label className="col-span-2 block text-xs">
                月份（* = 每月）
                <Input
                  className="mt-1 h-8"
                  value={month}
                  onChange={(e) => patchCron(3, e.target.value)}
                  placeholder="*"
                />
              </label>
            </div>
          )}

          {advanced && (
            <label className="block text-xs">
              cron 表达式（分 时 日 月 周，也可写 6 段带秒）
              <Input
                className="mt-1 font-mono"
                value={cronExpr}
                onChange={(e) => onChange({ cronExpr: e.target.value })}
                placeholder="0 9 * * *"
              />
            </label>
          )}

          <div className="flex items-center justify-between">
            <code className="text-muted-foreground text-xs">{cronExpr}</code>
            <Button
              type="button"
              size="sm"
              variant="ghost"
              onClick={() => setAdvanced((v) => !v)}
            >
              {advanced ? "用表单配置" : "手写表达式"}
            </Button>
          </div>
        </>
      )}

      {scheduleType === "EVERY_MINUTES" && (
        <div className="flex flex-wrap items-center gap-2">
          {PRESETS.filter((p) => p.type === "EVERY_MINUTES").map((preset) => (
            <Button
              key={preset.label}
              type="button"
              size="sm"
              variant={Number(intervalMinutes) === preset.interval ? "default" : "outline"}
              onClick={() => onChange({ intervalMinutes: String(preset.interval) })}
            >
              {preset.label}
            </Button>
          ))}
          <label className="flex items-center gap-2 text-xs">
            自定义
            <Input
              className="h-8 w-20"
              type="number"
              min={1}
              value={intervalMinutes}
              onChange={(e) => onChange({ intervalMinutes: e.target.value })}
            />
            分钟
          </label>
        </div>
      )}

      {scheduleType === "ONCE" && (
        <div className="flex flex-wrap items-end gap-2">
          <label className="block text-xs">
            执行时间
            <Input
              className="mt-1 h-8"
              type="datetime-local"
              value={onceAtLocal}
              onChange={(e) => onChange({ onceAtLocal: e.target.value })}
            />
          </label>
          {[10, 30, 60].map((minutes) => (
            <Button
              key={minutes}
              type="button"
              size="sm"
              variant="outline"
              onClick={() => {
                const at = new Date(Date.now() + minutes * 60_000);
                const pad = (n: number) => String(n).padStart(2, "0");
                onChange({
                  onceAtLocal: `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T${pad(at.getHours())}:${pad(at.getMinutes())}`,
                });
              }}
            >
              {minutes} 分钟后
            </Button>
          ))}
        </div>
      )}

      {/* 接下来 5 次：所见即所得 */}
      <div className="rounded bg-muted/40 p-2 text-xs">
        {previewError ? (
          <span className="text-destructive">计划不合法：{previewError}</span>
        ) : preview ? (
          <>
            <div className="text-muted-foreground mb-1">{preview.scheduleText}</div>
            {preview.nextRuns.length === 0 ? (
              <span className="text-destructive">这个表达式永远不会触发，请检查</span>
            ) : (
              <ul className="space-y-0.5">
                {preview.nextRuns.map((time, index) => (
                  <li key={time}>
                    <span className="text-muted-foreground">#{index + 1}</span>{" "}
                    {new Date(time).toLocaleString()}
                  </li>
                ))}
              </ul>
            )}
          </>
        ) : (
          <span className="text-muted-foreground">计算中…</span>
        )}
      </div>

      <button
        type="button"
        className="text-muted-foreground hover:text-foreground text-xs"
        onClick={() => setPeak((v) => !v)}
      >
        {peak ? "收起说明" : "cron 怎么写？"}
      </button>
      {peak && (
        <div className="text-muted-foreground space-y-1 text-xs">
          <div>5 段：分 时 日 月 周。例：</div>
          <div>
            <code>0 9 * * *</code> 每天 9:00，<code>30 18 * * 1-5</code> 工作日 18:30
          </div>
          <div>
            <code>0 9 1 * *</code> 每月 1 号 9:00，<code>*/30 * * * *</code> 每 30 分钟
          </div>
          <div>支持 <code>*</code>（任意）、<code>a-b</code>（范围）、<code>a,b</code>（列举）、<code>*/n</code>（步长）</div>
        </div>
      )}
    </div>
  );
}

/** 主要预设按钮里用到的加号图标占位（保持与其他页面一致的视觉）。 */
export const ScheduleEditorAddIcon = Plus;
