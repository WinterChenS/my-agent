import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import type { ApprovalStatus } from "@/lib/agui/types";

/**
 * 审批卡：展示被权限系统拦截的工具调用，并允许批准 / 拒绝。
 *
 * <p>决策后状态固定在卡片上（变灰显示结果），不再可点。
 */
export function ApprovalCard({
  toolName,
  args,
  message,
  status,
  busy,
  onDecide,
}: {
  toolName: string;
  args: unknown;
  message: string | null;
  status: ApprovalStatus;
  busy?: boolean;
  onDecide?: (decision: "APPROVED" | "DENIED") => void;
}) {
  const decided = status !== "pending";
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
            <Button size="sm" disabled={busy} onClick={() => onDecide?.("APPROVED")}>
              批准并继续
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => onDecide?.("DENIED")}
            >
              拒绝
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
