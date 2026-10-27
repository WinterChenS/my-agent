import { useCallback, useEffect, useState } from "react";
import { Loader2, PlayCircle, FlaskConical, CheckCircle2, XCircle } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { getRetryPolicy, testModelRetry, type RetryPolicy, type RetryTestResult } from "@/api/models";

/**
 * 模型「重试测试」弹窗。
 *
 * 为什么要有它：重试是静默的（日志里有 attempt、界面什么都没有），用户会怀疑"到底加没加"。
 * 这里给出两件事：
 * 1) 当前生效的策略（后端算好的一句话，前端不重复实现规则）；
 * 2) 能亲手点一下的验证：
 *    - 模拟断流：本地假端点第 1 次流出半句就断、第 2 次成功 —— 证明重试链路真的会重试，且不花真实额度；
 *    - 真实调用：用这个模型真发一次最小请求，报告过程中实际重试了几次（0 次 = 一次就成功，是好消息）。
 */
interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 要测试的模型；null 时只能看策略 */
  model: { id: number; modelId: string } | null;
}

export function RetryTestDialog({ open, onOpenChange, model }: Props) {
  const [policy, setPolicy] = useState<RetryPolicy | null>(null);
  const [running, setRunning] = useState<"simulate" | "live" | null>(null);
  const [result, setResult] = useState<RetryTestResult | null>(null);
  const [mode, setMode] = useState<"simulate" | "live" | null>(null);

  const loadPolicy = useCallback(async () => {
    try {
      setPolicy(await getRetryPolicy());
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "读取重试策略失败");
    }
  }, []);

  useEffect(() => {
    if (open) {
      setResult(null);
      setMode(null);
      void loadPolicy();
    }
  }, [open, loadPolicy]);

  const run = async (next: "simulate" | "live") => {
    if (!model) return;
    setRunning(next);
    setMode(next);
    setResult(null);
    try {
      const res = await testModelRetry(model.id, next === "simulate");
      setResult(res);
      if (res.ok) {
        toast.success(
          res.retries > 0 ? `重试 ${res.retries} 次后成功` : "一次调用即成功（未触发重试）",
        );
      } else {
        toast.error("测试失败");
      }
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "测试失败");
    } finally {
      setRunning(null);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>模型重试测试</DialogTitle>
          <DialogDescription>
            验证"调用失败会不会自动重试"。策略可在 application.yml 的{" "}
            <code className="text-xs">myagent.model.retry.*</code> 调整。
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="bg-muted/40 rounded-md border p-3 text-sm">
            {policy ? (
              <>
                <div className="mb-1 flex flex-wrap items-center gap-2">
                  <Badge variant="secondary">最多重试 {policy.maxRetries} 次</Badge>
                  <Badge variant="secondary">退避 {policy.initialBackoffMs}ms 起</Badge>
                  <Badge variant="secondary">超时 {policy.timeoutMinutes} 分钟</Badge>
                </div>
                <p className="text-muted-foreground text-xs leading-relaxed">{policy.summary}</p>
              </>
            ) : (
              <span className="text-muted-foreground text-xs">读取策略中…</span>
            )}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Button onClick={() => void run("simulate")} disabled={running !== null}>
              {running === "simulate" ? (
                <Loader2 className="size-4 animate-spin" />
              ) : (
                <FlaskConical className="size-4" />
              )}
              模拟断流自检
            </Button>
            <Button
              variant="outline"
              onClick={() => void run("live")}
              disabled={running !== null || !model}
            >
              {running === "live" ? (
                <Loader2 className="size-4 animate-spin" />
              ) : (
                <PlayCircle className="size-4" />
              )}
              真实调用测试{model ? `（${model.modelId}）` : ""}
            </Button>
            <span className="text-muted-foreground text-xs">
              模拟断流不花额度；真实调用会消耗一次极小的请求。
            </span>
          </div>

          {result ? (
            <div
              className={
                "rounded-md border p-3 text-sm " +
                (result.ok ? "border-emerald-500/40 bg-emerald-500/5" : "border-destructive/40 bg-destructive/5")
              }
            >
              <div className="flex items-center gap-2 font-medium">
                {result.ok ? (
                  <CheckCircle2 className="size-4 text-emerald-600" />
                ) : (
                  <XCircle className="size-4 text-destructive" />
                )}
                {result.ok ? "通过" : "不通过"}
                <span className="text-muted-foreground font-normal">
                  {mode === "simulate" ? "模拟断流" : "真实调用"} · 耗时 {result.elapsedMs}ms
                </span>
              </div>
              <ul className="text-muted-foreground mt-2 space-y-1 text-xs">
                <li>
                  可重试失败 <b>{result.failures}</b> 次 · 实际重试 <b>{result.retries}</b> 次
                  {result.retries === 0 && result.ok ? "（一次就成功，属于正常情况）" : ""}
                </li>
                {result.sampleText ? <li>返回内容：{result.sampleText}</li> : null}
                {result.error ? <li className="text-destructive">失败原因：{result.error}</li> : null}
              </ul>
            </div>
          ) : null}
        </div>
      </DialogContent>
    </Dialog>
  );
}

export default RetryTestDialog;
