import { useCallback, useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { History, Pause, Pencil, Play, Plus, RefreshCw, Trash2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { listAgentDetails, type AgentDetail } from "@/api/agents";
import { listChannels, type ChannelAppInfo } from "@/api/channels";
import { listSessions } from "@/api/sessions";
import {
  createScheduledTask,
  deleteScheduledTask,
  listChannelPeers,
  listScheduledTaskRuns,
  listScheduledTasks,
  runScheduledTaskNow,
  setScheduledTaskStatus,
  updateScheduledTask,
  type ScheduledTaskInfo,
  type ScheduledTaskRunInfo,
  type ScheduleType,
  type TaskTarget,
} from "@/api/scheduledTasks";
import ScheduleEditor from "./ScheduleEditor";

interface SessionOption {
  id: number;
  title: string | null;
  origin?: string;
}

interface PeerOption {
  peerKey: string;
  peerName: string | null;
  peerKind: string;
}

interface FormState {
  id: number | null;
  name: string;
  agentId: number | null;
  scheduleType: ScheduleType;
  cronExpr: string;
  intervalMinutes: string;
  onceAtLocal: string;
  timezone: string;
  prompt: string;
  targetType: TaskTarget;
  misfirePolicy: "COALESCE" | "SKIP";
  /** 运行/写回用的会话（决定 threadId → 记忆连续） */
  chatSessionId: number | null;
  /** 推送到 IM 时：渠道 + 接收人（不填则用运行会话的绑定） */
  channelAppId: number | null;
  peerKey: string;
}

const EMPTY: FormState = {
  id: null,
  name: "",
  agentId: null,
  scheduleType: "CRON",
  cronExpr: "0 9 * * *",
  intervalMinutes: "60",
  onceAtLocal: "",
  timezone: "Asia/Shanghai",
  prompt: "",
  targetType: "SESSION",
  misfirePolicy: "COALESCE",
  chatSessionId: null,
  channelAppId: null,
  peerKey: "",
};

/** ISO → datetime-local 输入框格式。 */
function toLocalInput(iso: string | null): string {
  if (!iso) return "";
  const date = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function statusBadge(status: ScheduledTaskInfo["status"]) {
  switch (status) {
    case "ACTIVE":
      return <Badge className="bg-emerald-600">运行中</Badge>;
    case "PAUSED":
      return <Badge variant="secondary">已暂停</Badge>;
    case "ERROR":
      return <Badge variant="destructive">异常</Badge>;
    default:
      return <Badge variant="outline">已完成</Badge>;
  }
}

function targetLabel(target: TaskTarget) {
  return target === "IM" ? "IM" : target === "NONE" ? "只记录" : "会话";
}

/** 定时任务管理：cron / 固定间隔 / 一次性，结果可回会话或推送到 IM（含附件）。 */
export default function SchedulesPage() {
  const [items, setItems] = useState<ScheduledTaskInfo[]>([]);
  const [agents, setAgents] = useState<AgentDetail[]>([]);
  const [sessions, setSessions] = useState<SessionOption[]>([]);
  const [channels, setChannels] = useState<ChannelAppInfo[]>([]);
  const [peers, setPeers] = useState<PeerOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [form, setForm] = useState<FormState | null>(null);
  const [saving, setSaving] = useState(false);
  const [runsOf, setRunsOf] = useState<ScheduledTaskInfo | null>(null);
  const [runs, setRuns] = useState<ScheduledTaskRunInfo[]>([]);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const [tasks, agentPage, sessionList, channelList] = await Promise.all([
        listScheduledTasks(),
        listAgentDetails(1, 200),
        listSessions().catch(() => [] as SessionOption[]),
        listChannels().catch(() => [] as ChannelAppInfo[]),
      ]);
      setItems(tasks);
      setAgents(agentPage.records ?? []);
      setSessions((sessionList ?? []).map((s) => ({ id: s.id, title: s.title, origin: s.origin })));
      setChannels(channelList ?? []);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载定时任务失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  // 选了渠道就拉它已绑定的接收人（含手工绑定的、还没聊过天的用户）
  useEffect(() => {
    const channelId = form?.channelAppId;
    if (!channelId) {
      setPeers([]);
      return;
    }
    let alive = true;
    listChannelPeers(channelId)
      .then((list) => {
        if (alive) {
          setPeers(
            list.map((p) => ({ peerKey: p.peerKey, peerName: p.peerName, peerKind: p.peerKind })),
          );
        }
      })
      .catch(() => {
        if (alive) setPeers([]);
      });
    return () => {
      alive = false;
    };
  }, [form?.channelAppId]);

  const agentOptions = useMemo(
    () => agents.filter((a) => a.permissionMode !== "REQUIRE_APPROVAL"),
    [agents],
  );

  function openCreate() {
    setForm({ ...EMPTY, agentId: agentOptions[0]?.id ?? null });
  }

  function openEdit(task: ScheduledTaskInfo) {
    setForm({
      id: task.id,
      name: task.name,
      agentId: task.agentId,
      scheduleType: task.scheduleType,
      cronExpr: task.cronExpr ?? "0 9 * * *",
      intervalMinutes: String(task.intervalMinutes ?? 60),
      onceAtLocal: toLocalInput(task.onceAt),
      timezone: task.timezone || "Asia/Shanghai",
      prompt: task.prompt,
      targetType: task.targetType,
      misfirePolicy: task.misfirePolicy,
      chatSessionId: task.chatSessionId,
      channelAppId: task.channelAppId,
      peerKey: task.peerKey ?? "",
    });
  }

  async function save() {
    if (!form) return;
    if (!form.name.trim()) return toast.error("请填写任务名称");
    if (!form.agentId) return toast.error("请选择执行的 Agent");
    if (!form.prompt.trim()) return toast.error("请填写要执行的指令");
    if (form.scheduleType === "CRON" && !form.cronExpr.trim()) return toast.error("请填写 cron 表达式");
    if (form.scheduleType === "ONCE" && !form.onceAtLocal) return toast.error("请选择执行时间");
    if (form.targetType === "IM" && !form.peerKey && !form.chatSessionId) {
      return toast.error("推送到 IM 需要选接收人，或选一个从 IM 来的会话");
    }

    const payload = {
      name: form.name.trim(),
      agentId: form.agentId,
      scheduleType: form.scheduleType,
      cronExpr: form.scheduleType === "CRON" ? form.cronExpr.trim() : null,
      intervalMinutes: form.scheduleType === "EVERY_MINUTES" ? Number(form.intervalMinutes) : null,
      onceAt: form.scheduleType === "ONCE" ? new Date(form.onceAtLocal).toISOString() : null,
      timezone: form.timezone,
      prompt: form.prompt.trim(),
      targetType: form.targetType,
      misfirePolicy: form.misfirePolicy,
      chatSessionId: form.chatSessionId,
      channelAppId: form.targetType === "IM" ? form.channelAppId : null,
      peerKey: form.targetType === "IM" ? form.peerKey || null : null,
    };
    setSaving(true);
    try {
      if (form.id) {
        await updateScheduledTask(form.id, payload);
        toast.success("已保存");
      } else {
        await createScheduledTask(payload);
        toast.success("已创建");
      }
      setForm(null);
      await reload();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败");
    } finally {
      setSaving(false);
    }
  }

  async function toggle(task: ScheduledTaskInfo) {
    try {
      await setScheduledTaskStatus(task.id, task.status === "ACTIVE" ? "PAUSED" : "ACTIVE");
      toast.success(task.status === "ACTIVE" ? "已暂停" : "已恢复");
      await reload();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "操作失败");
    }
  }

  async function runNow(task: ScheduledTaskInfo) {
    try {
      await runScheduledTaskNow(task.id);
      toast.success("已触发执行，稍后可在这里看结果");
      setTimeout(() => void reload(), 3000);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "触发失败");
    }
  }

  async function remove(task: ScheduledTaskInfo) {
    if (!window.confirm(`删除定时任务「${task.name}」？`)) return;
    try {
      await deleteScheduledTask(task.id);
      toast.success("已删除");
      await reload();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "删除失败");
    }
  }

  async function openRuns(task: ScheduledTaskInfo) {
    setRunsOf(task);
    try {
      setRuns(await listScheduledTaskRuns(task.id));
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载执行记录失败");
    }
  }

  return (
    <div className="space-y-4 p-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">定时任务</h1>
          <p className="text-muted-foreground mt-1 text-sm">
            到点让 Agent 自动执行指令；生成的文件会随结果一起发出去。多实例部署不会重复执行。
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => void reload()} disabled={loading}>
            <RefreshCw className={`mr-2 h-4 w-4 ${loading ? "animate-spin" : ""}`} />
            刷新
          </Button>
          <Button onClick={openCreate}>
            <Plus className="mr-2 h-4 w-4" />
            新建任务
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">任务列表</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>名称</TableHead>
                <TableHead>Agent</TableHead>
                <TableHead>计划</TableHead>
                <TableHead>下次执行</TableHead>
                <TableHead>发到</TableHead>
                <TableHead>状态</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} className="text-muted-foreground text-center">
                    还没有定时任务。也可以在聊天里说「每天 9 点把昨天的数据汇总发我」。
                  </TableCell>
                </TableRow>
              )}
              {items.map((task) => (
                <TableRow key={task.id}>
                  <TableCell className="font-medium">
                    {task.name}
                    <div
                      className="text-muted-foreground max-w-[260px] truncate text-xs"
                      title={task.prompt}
                    >
                      {task.prompt}
                    </div>
                  </TableCell>
                  <TableCell>{task.agentName ?? task.agentId}</TableCell>
                  <TableCell className="text-xs">{task.scheduleText}</TableCell>
                  <TableCell className="text-xs">
                    {task.nextRunAt ? new Date(task.nextRunAt).toLocaleString() : "-"}
                    <div className="text-muted-foreground">已跑 {task.runCount} 次</div>
                  </TableCell>
                  <TableCell className="max-w-[180px] text-xs">
                    <div>{targetLabel(task.targetType)}</div>
                    <div className="text-muted-foreground truncate" title={task.targetText}>
                      {task.targetText ?? ""}
                    </div>
                  </TableCell>
                  <TableCell>
                    {statusBadge(task.status)}
                    {task.lastError && (
                      <div
                        className="text-destructive mt-1 max-w-[200px] truncate text-xs"
                        title={task.lastError}
                      >
                        {task.lastError}
                      </div>
                    )}
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button size="sm" variant="ghost" title="执行记录" onClick={() => void openRuns(task)}>
                        <History className="h-4 w-4" />
                      </Button>
                      <Button size="sm" variant="ghost" title="立即执行" onClick={() => void runNow(task)}>
                        <Play className="h-4 w-4" />
                      </Button>
                      <Button size="sm" variant="ghost" title="暂停/恢复" onClick={() => void toggle(task)}>
                        <Pause className="h-4 w-4" />
                      </Button>
                      <Button size="sm" variant="ghost" title="编辑" onClick={() => openEdit(task)}>
                        <Pencil className="h-4 w-4" />
                      </Button>
                      <Button size="sm" variant="ghost" title="删除" onClick={() => void remove(task)}>
                        <Trash2 className="h-4 w-4 text-red-500" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* 新建/编辑 */}
      <Dialog open={form !== null} onOpenChange={(open) => !open && setForm(null)}>
        <DialogContent className="max-h-[88vh] max-w-2xl overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{form?.id ? "编辑定时任务" : "新建定时任务"}</DialogTitle>
            <DialogDescription>
              指令要能独立执行（到点时没有当前对话上下文）。生成的文件会随结果一起发送。
            </DialogDescription>
          </DialogHeader>
          {form && (
            <div className="space-y-3">
              <label className="block text-sm">
                任务名称
                <Input
                  className="mt-1"
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="例如：每日销售汇总"
                />
              </label>
              <label className="block text-sm">
                执行 Agent
                <select
                  className="border-input bg-background mt-1 h-9 w-full rounded-md border px-2 text-sm"
                  value={form.agentId ?? ""}
                  onChange={(e) =>
                    setForm({ ...form, agentId: e.target.value ? Number(e.target.value) : null })
                  }
                >
                  <option value="">请选择</option>
                  {agentOptions.map((agent) => (
                    <option key={agent.id} value={agent.id}>
                      {agent.name}（{agent.agentId}）
                    </option>
                  ))}
                </select>
              </label>

              <label className="block text-sm">
                计划类型
                <select
                  className="border-input bg-background mt-1 h-9 w-full rounded-md border px-2 text-sm"
                  value={form.scheduleType}
                  onChange={(e) => setForm({ ...form, scheduleType: e.target.value as ScheduleType })}
                >
                  <option value="CRON">按 cron（每天/每周固定时间）</option>
                  <option value="EVERY_MINUTES">固定间隔</option>
                  <option value="ONCE">一次性（指定时间）</option>
                </select>
              </label>

              <ScheduleEditor
                scheduleType={form.scheduleType}
                cronExpr={form.cronExpr}
                intervalMinutes={form.intervalMinutes}
                onceAtLocal={form.onceAtLocal}
                timezone={form.timezone}
                onChange={(patch) => setForm({ ...form, ...patch })}
              />

              <label className="block text-sm">
                时区
                <Input
                  className="mt-1"
                  value={form.timezone}
                  onChange={(e) => setForm({ ...form, timezone: e.target.value })}
                />
              </label>
              <label className="block text-sm">
                指令（到点执行）
                <textarea
                  className="border-input bg-background mt-1 min-h-[90px] w-full rounded-md border p-2 text-sm"
                  value={form.prompt}
                  onChange={(e) => setForm({ ...form, prompt: e.target.value })}
                  placeholder="例如：把 uploads 里昨天的销售表汇总成一份 markdown 报告"
                />
              </label>

              <div className="grid grid-cols-2 gap-3">
                <label className="block text-sm">
                  结果发到
                  <select
                    className="border-input bg-background mt-1 h-9 w-full rounded-md border px-2 text-sm"
                    value={form.targetType}
                    onChange={(e) => setForm({ ...form, targetType: e.target.value as TaskTarget })}
                  >
                    <option value="SESSION">会话（网页可见）</option>
                    <option value="IM">IM（钉钉推送）</option>
                    <option value="NONE">只记录</option>
                  </select>
                </label>
                <label className="block text-sm">
                  错过时
                  <select
                    className="border-input bg-background mt-1 h-9 w-full rounded-md border px-2 text-sm"
                    value={form.misfirePolicy}
                    onChange={(e) =>
                      setForm({ ...form, misfirePolicy: e.target.value as "COALESCE" | "SKIP" })
                    }
                  >
                    <option value="COALESCE">补跑一次</option>
                    <option value="SKIP">跳过</option>
                  </select>
                </label>
              </div>

              <label className="block text-sm">
                运行会话（决定上下文与记忆；不选则自动新建）
                <select
                  className="border-input bg-background mt-1 h-9 w-full rounded-md border px-2 text-sm"
                  value={form.chatSessionId ?? ""}
                  onChange={(e) =>
                    setForm({
                      ...form,
                      chatSessionId: e.target.value ? Number(e.target.value) : null,
                    })
                  }
                >
                  <option value="">自动新建</option>
                  {sessions.map((session) => (
                    <option key={session.id} value={session.id}>
                      #{session.id} {session.title ?? "未命名"}
                      {session.origin === "DINGTALK" ? "（钉钉）" : ""}
                    </option>
                  ))}
                </select>
              </label>

              {form.targetType === "IM" && (
                <>
                  <div className="grid grid-cols-2 gap-3">
                    <label className="block text-sm">
                      渠道
                      <select
                        className="border-input bg-background mt-1 h-9 w-full rounded-md border px-2 text-sm"
                        value={form.channelAppId ?? ""}
                        onChange={(e) =>
                          setForm({
                            ...form,
                            channelAppId: e.target.value ? Number(e.target.value) : null,
                            peerKey: "",
                          })
                        }
                      >
                        <option value="">用运行会话的绑定</option>
                        {channels.map((channel) => (
                          <option key={channel.id} value={channel.id}>
                            {channel.name}（{channel.channelType}）
                          </option>
                        ))}
                      </select>
                    </label>
                    <label className="block text-sm">
                      接收人
                      <select
                        className="border-input bg-background mt-1 h-9 w-full rounded-md border px-2 text-sm"
                        value={form.peerKey}
                        onChange={(e) => setForm({ ...form, peerKey: e.target.value })}
                      >
                        <option value="">用运行会话的绑定</option>
                        {peers.map((peer) => (
                          <option key={peer.peerKey} value={peer.peerKey}>
                            {peer.peerName ?? peer.peerKey}
                            {peer.peerKind === "GROUP" ? "（群）" : ""}
                          </option>
                        ))}
                        {form.peerKey && !peers.some((p) => p.peerKey === form.peerKey) && (
                          <option value={form.peerKey}>已绑定：{form.peerKey}</option>
                        )}
                      </select>
                    </label>
                  </div>
                  <p className="text-muted-foreground text-xs">
                    接收人可在「IM 渠道 → 会话」里手工绑定（用户还没跟机器人说过话时用）。
                    未选接收人时，会用「运行会话」对应的 IM 绑定。
                  </p>
                </>
              )}
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setForm(null)}>
              取消
            </Button>
            <Button onClick={() => void save()} disabled={saving}>
              {saving ? "保存中…" : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 执行记录 */}
      <Dialog open={runsOf !== null} onOpenChange={(open) => !open && setRunsOf(null)}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>「{runsOf?.name}」执行记录</DialogTitle>
            <DialogDescription>
              最近 20 次。失败的会记录原因，连续失败 3 次会自动暂停。
            </DialogDescription>
          </DialogHeader>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>开始时间</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>耗时</TableHead>
                <TableHead>输出 / 错误</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {runs.length === 0 && (
                <TableRow>
                  <TableCell colSpan={4} className="text-muted-foreground text-center">
                    还没有执行记录。
                  </TableCell>
                </TableRow>
              )}
              {runs.map((run) => (
                <TableRow key={run.id}>
                  <TableCell className="text-xs">{new Date(run.startedAt).toLocaleString()}</TableCell>
                  <TableCell>
                    <Badge
                      variant={
                        run.status === "SUCCESS"
                          ? "default"
                          : run.status === "FAILED"
                            ? "destructive"
                            : "secondary"
                      }
                    >
                      {run.status}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-xs">
                    {run.durationMs ? `${run.durationMs}ms` : "-"}
                  </TableCell>
                  <TableCell className="max-w-[420px] text-xs">
                    <div className="truncate" title={run.error ?? run.outputPreview ?? ""}>
                      {run.error ?? run.outputPreview ?? "-"}
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </DialogContent>
      </Dialog>
    </div>
  );
}
