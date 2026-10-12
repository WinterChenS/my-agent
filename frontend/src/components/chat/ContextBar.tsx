import { Button } from "@/components/ui/button";
import type { ContextUsage } from "@/api/sessions";

/**
 * 上下文用量条（tier1-upgrades ②）。
 *
 * 输入框上方常驻：显示「当前上下文估算 tokens / 压缩触发阈值」，
 * 接近阈值变黄、超过阈值变红；并提供「压缩上下文」「清空上下文」两个动作。
 */
export function ContextBar({
  usage,
  busy,
  onCompact,
  onClear,
}: {
  usage: ContextUsage | null;
  busy: boolean;
  onCompact: () => void;
  onClear: () => void;
}) {
  if (!usage) {
    return null;
  }
  const ratio = usage.triggerTokens > 0 ? usage.estimatedTokens / usage.triggerTokens : 0;
  const level = ratio >= 1 ? "over" : ratio >= 0.8 ? "near" : "ok";
  const barColor =
    level === "over" ? "bg-red-500" : level === "near" ? "bg-amber-500" : "bg-emerald-500";
  const textColor =
    level === "over" ? "text-red-600" : level === "near" ? "text-amber-600" : "text-muted-foreground";
  const percent = Math.max(0, Math.min(100, Math.round(ratio * 100)));

  return (
    <div className="mb-2 rounded-md border px-3 py-2 text-xs" data-testid="context-bar">
      <div className="flex items-center justify-between gap-2">
        <div className={`flex items-center gap-2 ${textColor}`} data-testid="context-usage">
          <span>
            上下文 {usage.estimatedTokens.toLocaleString()} / 触发阈值{" "}
            {usage.triggerTokens.toLocaleString()} tokens（{percent}%）
          </span>
          {usage.compacted && <span className="rounded bg-muted px-1">已压缩过</span>}
          {level === "near" && <span>接近阈值，建议压缩</span>}
          {level === "over" && <span>已达阈值，下一轮将自动压缩</span>}
        </div>
        <div className="flex shrink-0 gap-2">
          <Button
            variant="outline"
            size="sm"
            data-testid="context-compact"
            disabled={busy}
            onClick={onCompact}
          >
            压缩上下文
          </Button>
          <Button
            variant="outline"
            size="sm"
            data-testid="context-clear"
            disabled={busy}
            onClick={onClear}
          >
            清空上下文
          </Button>
        </div>
      </div>
      <div className="mt-1 h-1 w-full overflow-hidden rounded bg-muted">
        <div className={`h-full ${barColor}`} style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}
