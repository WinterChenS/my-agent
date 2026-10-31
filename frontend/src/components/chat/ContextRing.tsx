import { AlertTriangle, Eraser, Minimize2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import type { ContextUsage } from "@/api/sessions";

/**
 * 上下文容量圆环（tier1-upgrades ②）——放在发送键旁边的**一眼可见**指示器。
 *
 * 设计取舍：以前是输入框上方一整条用量条，占地方又难看。改成 32px 圆环：
 * 环的填充比例 = 当前上下文 / **模型生效的上下文窗口**（配了就用配的，没配用后端兜底值）；
 * 颜色分三档（正常/接近/超阈值）仍跟随**压缩触发阈值**。
 * 具体数字、压缩、清空都收进点击后的浮层里，输入区保持干净。
 *
 * 「清空上下文」不可逆（模型上下文归零），所以必须**二次确认**。
 */
export function ContextRing({
  usage,
  busy,
  notice,
  onCompact,
  onClear,
}: {
  usage: ContextUsage | null;
  busy: boolean;
  /** 操作结果反馈（由父组件传入，展示在浮层里）。 */
  notice: string | null;
  onCompact: () => void;
  onClear: () => void;
}) {
  if (!usage) {
    return null;
  }

  const ratio = usage.triggerTokens > 0 ? usage.estimatedTokens / usage.triggerTokens : 0;
  /*
   * 圆的填充比例按**模型生效的上下文窗口**算（模型配的上下文长度就体现在这里；没配则用兜底值）。
   * 颜色/提示仍跟随**压缩触发阈值** —— 那才决定“下一轮会不会被自动压缩”。
   * 两个数字在详情里都显示，不藏信息。
   */
  const windowTokens = Number(usage.contextWindow) > 0 ? Number(usage.contextWindow) : 0;
  const windowRatio = windowTokens > 0 ? usage.estimatedTokens / windowTokens : 0;
  const exactPercent = Math.max(0, Math.min(100, windowRatio * 100));
  /*
   * 百分比展示：**不要四舍五入到 0**。
   * 上下文窗口是 1,000,000 时，一轮几千 token 的对话只有 0.3%~0.8%，
   * 取整后一直显示 0%（用户反馈"明明输出了很多内容，一直是 0"）。
   */
  const percentText =
    usage.estimatedTokens <= 0
      ? "0"
      : exactPercent < 0.1
        ? "<0.1"
        : exactPercent < 1
          ? exactPercent.toFixed(1)
          : String(Math.round(exactPercent));
  const percent = exactPercent; // 圆环/进度条按真实比例画

  /**
   * 圆环中心显示 **token 数**而不是百分比。
   *
   * <p>为什么：模型窗口是 1,000,000，一段几千 token 的对话只有 0.x%，取整后永远是 0%——
   * 用户看到"明明输出了很多内容，一直是 0"（实测反馈）。token 数会跟着输出稳定增长，
   * 百分比仍保留在 tooltip / 详情里，圆环的填充长度也照旧按比例画。
   */
  const compactTokens = (value: number): string => {
    if (value < 1000) return String(value);
    if (value < 10000) return `${(value / 1000).toFixed(1).replace(/\.0$/, "")}k`;
    if (value < 1000000) return `${Math.round(value / 1000)}k`;
    return `${(value / 1000000).toFixed(1).replace(/\.0$/, "")}M`;
  };
  const ringText = compactTokens(usage.estimatedTokens);
  const level = ratio >= 1 ? "over" : ratio >= 0.8 ? "near" : "ok";
  const strokeColor =
    level === "over"
      ? "stroke-red-500"
      : level === "near"
        ? "stroke-amber-500"
        : "stroke-emerald-500";
  const barColor =
    level === "over" ? "bg-red-500" : level === "near" ? "bg-amber-500" : "bg-emerald-500";
  const textColor =
    level === "over" ? "text-red-600" : level === "near" ? "text-amber-600" : "text-muted-foreground";
  const hint =
    level === "over"
      ? "已达触发阈值，下一轮会自动压缩"
      : level === "near"
        ? "接近阈值，建议手动压缩"
        : "上下文充足";

  // 半径 14 的圆周长 ≈ 87.96；用 dasharray 画出已用比例
  const circumference = 2 * Math.PI * 14;
  const dash = (percent / 100) * circumference;

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          className="size-9 shrink-0"
          data-testid="context-ring"
          title={`上下文 ${usage.estimatedTokens.toLocaleString()} / ${
            windowTokens > 0 ? windowTokens.toLocaleString() : "—"
          } tokens（${percentText}%，点击查看详情）`}
          aria-label={`上下文占窗口 ${percentText}%`}
        >
          <span className="relative inline-flex size-7 items-center justify-center">
            <svg viewBox="0 0 32 32" className="size-7 -rotate-90">
              <circle cx="16" cy="16" r="14" className="stroke-muted fill-none" strokeWidth="3" />
              <circle
                cx="16"
                cy="16"
                r="14"
                className={`fill-none ${strokeColor} transition-[stroke-dasharray] duration-500`}
                strokeWidth="3"
                strokeLinecap="round"
                strokeDasharray={`${dash} ${circumference}`}
              />
            </svg>
            <span
              className={`absolute text-[9px] leading-none font-medium ${textColor}`}
              data-testid="context-ring-percent"
              data-percent={percentText}
            >
              {ringText}
            </span>
          </span>
        </Button>
      </PopoverTrigger>

      <PopoverContent align="end" side="top" className="w-80">
        <div className="flex items-start justify-between gap-2">
          <p className="text-sm font-medium">上下文用量</p>
          <span className={`text-right text-xs ${textColor}`} data-testid="context-usage">
            上下文 {usage.estimatedTokens.toLocaleString()} / 窗口{" "}
            {windowTokens > 0 ? windowTokens.toLocaleString() : "—"} tokens（{percentText}%）· 触发阈值{" "}
            {usage.triggerTokens.toLocaleString()} tokens
            {/* 把阈值和窗口的关系写出来：否则大窗口只有 11% 就被压缩时，用户会以为是 bug（实际反馈过） */}
            {windowTokens > 0 ? `（窗口的 ${Math.round((usage.triggerTokens / windowTokens) * 100)}%）` : ""}
          </span>
        </div>

        {/* 进度条：与圆环同色，给出更精确的视觉比例 */}
        <div className="bg-muted mt-2 h-1.5 w-full overflow-hidden rounded">
          <div className={`h-full ${barColor}`} style={{ width: `${percent}%` }} />
        </div>

        <dl className="mt-3 space-y-1 text-xs">
          <div className="flex justify-between">
            <dt className="text-muted-foreground">消息条数</dt>
            <dd>{usage.messages}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-muted-foreground">保留阈值</dt>
            <dd>{usage.keepTokens.toLocaleString()} tokens</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-muted-foreground">状态</dt>
            <dd>{usage.compacted ? "已压缩过" : "未压缩"}</dd>
          </div>
          {usage.summaryPreview && (
            <div className="pt-1">
              <dt className="text-muted-foreground">摘要</dt>
              <dd className="mt-0.5 line-clamp-3 text-[11px]">{usage.summaryPreview}</dd>
            </div>
          )}
        </dl>

        <p className={`mt-2 flex items-center gap-1 text-xs ${textColor}`}>
          {(level === "near" || level === "over") && <AlertTriangle className="size-3" />}
          {hint}
        </p>

        {notice && (
          <p className="text-muted-foreground mt-2 text-xs" data-testid="context-notice">
            {notice}
          </p>
        )}

        <div className="mt-3 flex gap-2">
          <Button
            variant="outline"
            size="sm"
            className="flex-1"
            data-testid="context-compact"
            disabled={busy}
            onClick={onCompact}
          >
            <Minimize2 className="size-3.5" />
            压缩上下文
          </Button>

          <AlertDialog>
            <AlertDialogTrigger asChild>
              <Button
                variant="outline"
                size="sm"
                className="text-destructive border-destructive/40 hover:bg-destructive/10 flex-1"
                data-testid="context-clear"
                disabled={busy}
              >
                <Eraser className="size-3.5" />
                清空上下文
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogTitle>清空这个会话的上下文？</AlertDialogTitle>
              <AlertDialogDescription>
                模型当前记住的对话上下文会被清空，下一轮它会从零开始。
                <br />
                聊天记录和已生成的产物都会保留，不会丢。
              </AlertDialogDescription>
              <div className="mt-4 flex justify-end gap-2">
                <AlertDialogCancel data-testid="context-clear-cancel">取消</AlertDialogCancel>
                <AlertDialogAction data-testid="context-clear-confirm" onClick={onClear}>
                  <Eraser className="size-3.5" />
                  确认清空
                </AlertDialogAction>
              </div>
            </AlertDialogContent>
          </AlertDialog>
        </div>
      </PopoverContent>
    </Popover>
  );
}
