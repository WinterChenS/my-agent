import { toast } from "sonner";
import { Ban, CheckCircle2, Clock, Loader2, RotateCcw, XCircle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cancelRun, rerunRun, type RunStatus, type RunTimelineItem } from "@/api/scheduledTasks";

function statusIcon(status: RunStatus) {
  switch (status) {
    case "SUCCESS":
      return <CheckCircle2 className="mt-0.5 size-4 text-emerald-600" />;
    case "FAILED":
    case "ABANDONED":
      return <XCircle className="text-destructive mt-0.5 size-4" />;
    case "CANCELLED":
      return <Ban className="text-muted-foreground mt-0.5 size-4" />;
    default:
      return <Loader2 className="text-primary mt-0.5 size-4 animate-spin" />;
  }
}

function statusBadge(status: RunStatus) {
  switch (status) {
    case "SUCCESS":
      return <Badge className="bg-emerald-600">成功</Badge>;
    case "FAILED":
      return <Badge variant="destructive">失败</Badge>;
    case "ABANDONED":
      return <Badge variant="outline">中断（节点退出）</Badge>;
    case "CANCELLED":
      return <Badge variant="secondary">已取消</Badge>;
    default:
      return <Badge>执行中</Badge>;
  }
}

interface Props {
  runs: RunTimelineItem[];
  loading: boolean;
  /** 选中日期（用于标题与空态文案） */
  day: string;
  onCanceled?: () => void;
}

/**
 * 执行时间轴：与日历同一天的数据，按时间倒序一条条排下来。
 *
 * <p>「执行中」的记录可以取消：后端会 dispose 掉 agent 的流订阅并调用官方
 * {@code interrupt}，落库状态记为「已取消」（不算失败，不影响自动暂停）。
 */
export default function RunTimeline({ runs, loading, day, onCanceled }: Props) {
  async function handleRerun(run: RunTimelineItem) {
    try {
      await rerunRun(run.id);
      toast.success(`已重新执行「${run.task_name}」，结果稍后出现在时间轴`);
      // 新记录要等执行线程落库，稍等一下再刷新
      await new Promise((resolve) => setTimeout(resolve, 1500));
      onCanceled?.();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "重新执行失败");
    }
  }

  async function handleCancel(run: RunTimelineItem) {
    try {
      const result = await cancelRun(run.id);
      if (result.cancelled) {
        toast.success(`已取消「${run.task_name}」的这次执行`);
        // 取消是"异步收尾"：执行线程要落库成「已取消」才看得到。
        // 立刻刷新会读到 RUNNING（用户会以为没取消成功），所以稍等一下再刷。
        await new Promise((resolve) => setTimeout(resolve, 1500));
      } else {
        toast.info("这次执行已经结束了（可能是刚跑完）");
      }
      onCanceled?.();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "取消失败");
    }
  }

  return (
    <div className="rounded-md border p-3" data-testid="run-timeline">
      <div className="mb-2 flex items-center justify-between">
        <div className="text-sm font-medium">
          {day} 的执行记录
          <span className="text-muted-foreground ml-2 text-xs">共 {runs.length} 条</span>
        </div>
        {loading && <Loader2 className="text-muted-foreground size-4 animate-spin" />}
      </div>

      {runs.length === 0 ? (
        <div className="text-muted-foreground py-8 text-center text-xs">
          这一天还没有执行记录。定时任务触发后会自动出现在这里。
        </div>
      ) : (
        <ol className="relative ml-2 border-l pl-4">
          {runs.map((run) => (
            <li key={run.id} className="mb-4 last:mb-0" data-testid={`timeline-item-${run.id}`}>
              <span className="bg-background absolute -left-[7px] flex size-3 items-center justify-center rounded-full border" />
              <div className="flex items-start gap-2">
                {statusIcon(run.status)}
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="truncate text-sm font-medium">{run.task_name}</span>
                    {statusBadge(run.status)}
                    <span className="text-muted-foreground flex items-center gap-1 text-xs">
                      <Clock className="size-3" />
                      {new Date(run.started_at).toLocaleTimeString()}
                      {run.duration_ms != null && ` · ${(run.duration_ms / 1000).toFixed(1)}s`}
                      {run.trigger_type === "MANUAL" && " · 手动"}
                    </span>
                  </div>
                  {(run.error || run.output_preview) && (
                    <div
                      className={`mt-1 line-clamp-2 text-xs ${
                        run.error ? "text-destructive" : "text-muted-foreground"
                      }`}
                      title={run.error ?? run.output_preview ?? ""}
                    >
                      {run.error ?? run.output_preview}
                    </div>
                  )}
                </div>
                <div className="flex shrink-0 gap-1">
                  {run.status === "RUNNING" ? (
                    <Button size="sm" variant="outline" onClick={() => void handleCancel(run)}>
                      取消执行
                    </Button>
                  ) : (
                    <Button size="sm" variant="outline" onClick={() => void handleRerun(run)}>
                      <RotateCcw className="mr-1 size-3.5" />
                      重新执行
                    </Button>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
