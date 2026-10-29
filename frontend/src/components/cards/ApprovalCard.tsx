import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import type { ApprovalStatus } from "@/lib/agui/types";

/**
 * 审批卡：展示被权限系统拦截的工具调用，并允许批准 / 拒绝。
 *
 * <p>决策后状态固定在卡片上（变灰显示结果），不再可点。
 *
 * <p>为什么不用父组件的 {@code busy} 来禁用：Agent 还在输出时 {@code busy} 是 true，
 * 按钮就一直灰着点不动，用户体感是"卡片卡死了"（实测反馈）。审批本身是**暂停中**的决策，
 * 应该立刻能点；这里只用自己的"提交中"状态防连点，并在超时后自动恢复可点。
 */
export function ApprovalCard({
  toolName,
  args,
  message,
  status,
  onDecide,
}: {
  toolName: string;
  args: unknown;
  message: string | null;
  status: ApprovalStatus;
  /** @deprecated 不再用父组件的 busy 禁用按钮（会把"暂停中"的审批卡也弄成点不动的假卡死）。 */
  busy?: boolean;
  onDecide?: (decision: "APPROVED" | "DENIED") => void;
}) {
  const decided = status !== "pending";
  const [submitting, setSubmitting] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => {
    if (timer.current) {
      clearTimeout(timer.current);
    }
  }, []);

  const decide = (decision: "APPROVED" | "DENIED") => {
    setSubmitting(true);
    // 父组件提交失败时不会回调我们，15 秒兜底恢复可点（否则一直灰着）
    timer.current = setTimeout(() => setSubmitting(false), 15_000);
    onDecide?.(decision);
  };
  let argsText: string;
  try {
    argsText = typeof args === "string" ? args : JSON.stringify(args ?? {}, null, 2);
  } catch {
    argsText = String(args);
  }

  return (
    <Card className={decided ? "opacity-70" : "border-amber-400/70"}>
      <CardHeader className="p-3">
        <CardTitle className="flex flex-wrap items-center gap-2 text-sm">
          <Badge variant={decided ? "secondary" : "destructive"}>
            {status === "pending" ? "需要审批" : status === "approved" ? "已批准" : "已拒绝"}
          </Badge>
          <span className="font-mono text-xs">{toolName}</span>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2 p-3 pt-0">
        {message && <p className="text-muted-foreground text-xs">{message}</p>}
        <div>
          <div className="text-muted-foreground mb-1 text-xs">调用参数</div>
          <pre className="bg-muted max-h-56 overflow-auto rounded-md p-2 text-xs">{argsText}</pre>
        </div>
        {!decided && (
          <div className="flex gap-2">
            <Button size="sm" disabled={submitting} onClick={() => decide("APPROVED")}>
              批准并继续
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={submitting}
              onClick={() => decide("DENIED")}
            >
              拒绝
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
