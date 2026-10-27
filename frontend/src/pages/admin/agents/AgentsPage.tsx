import { useCallback, useEffect, useState } from "react";
import { listUsers, type UserRow } from "@/api/users";
import { setAgentAllowedUsers } from "@/api/agents";
import { toast } from "sonner";
import { Plus, RefreshCw, Trash2, Pencil } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
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
import {
  PERMISSION_MODES,
  createAgent,
  deleteAgent,
  listAgentDetails,
  updateAgent,
  type AgentDetail,
  type PermissionMode,
} from "@/api/agents";
import { listModels, type ModelInfo } from "@/api/models";
import { listSkills, type SkillInfo } from "@/api/skills";
import { listMcpServers, type McpServerInfo } from "@/api/mcp";
import { maxTokensWarning } from "@/lib/utils";

interface FormState {
  id: number | null;
  name: string;
  agentId: string;
  sysPrompt: string;
  modelId: number | null;
  temperature: string;
  maxTokens: string;
  permissionMode: PermissionMode;
  maxIterations: string;
  /** tier1-upgrades ④：Agent 级额度（空=不限） */
  dailyQuota: string;
  monthlyQuota: string;
  quotaCurrency: string;
  status: "ENABLED" | "DISABLED";
  skillIds: number[];
  mcpServerIds: number[];
}

const EMPTY: FormState = {
  id: null,
  name: "",
  agentId: "",
  sysPrompt: "你是一个乐于助人的助手。",
  modelId: null,
  temperature: "0.7",
  maxTokens: "",
  permissionMode: "ALWAYS_ALLOW",
  // 技能/工具类任务很吃步数（读几份文档 + 跑几次命令就十几个），默认给宽一点
  maxIterations: "100",
  dailyQuota: "",
  monthlyQuota: "",
  quotaCurrency: "CNY",
  status: "ENABLED",
  skillIds: [],
  mcpServerIds: [],
};

const MODE_LABEL: Record<PermissionMode, string> = {
  ALWAYS_ALLOW: "直接执行",
  REQUIRE_APPROVAL: "需要审批（HITL）",
  ALWAYS_DENY: "一律拒绝",
};

export default function AgentsPage() {
  const [agents, setAgents] = useState<AgentDetail[]>([]);
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [servers, setServers] = useState<McpServerInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY);
  /** 全部用户（用于「可用用户」多选；空数组 = 对所有人开放） */
  const [users, setUsers] = useState<UserRow[]>([]);
  const [allowedUserIds, setAllowedUserIds] = useState<number[]>([]);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [a, m, s, c] = await Promise.all([
        listAgentDetails(),
        listModels(),
        listSkills(),
        listMcpServers(),
      ]);
      setAgents(a.records);
      setModels(m.records);
      setSkills(s.records);
      setServers(c.records);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载 Agent 列表失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // 用户列表：给「可用用户」多选用（该页仅 ADMIN 可进，/api/users 对 ADMIN 开放）
  useEffect(() => {
    listUsers({ current: 1, size: 200 })
      .then((page) => setUsers(page.records ?? []))
      .catch(() => setUsers([]));
  }, []);

  function toggle(list: number[], id: number): number[] {
    return list.includes(id) ? list.filter((x) => x !== id) : [...list, id];
  }

  async function save() {
    if (!form.name.trim() || !form.agentId.trim()) {
      toast.error("name 与 agentId 不能为空");
      return;
    }
    if (form.modelId == null) {
      toast.error("请选择模型（需先在「模型」页建好）");
      return;
    }
    const payload = {
      name: form.name.trim(),
      agentId: form.agentId.trim(),
      sysPrompt: form.sysPrompt,
      modelId: form.modelId,
      temperature: form.temperature.trim() ? Number(form.temperature) : null,
      maxTokens: form.maxTokens.trim() ? Number(form.maxTokens) : null,
      permissionMode: form.permissionMode,
      maxIterations: form.maxIterations.trim() ? Number(form.maxIterations) : null,
      dailyQuota: form.dailyQuota.trim() ? Number(form.dailyQuota) : null,
      monthlyQuota: form.monthlyQuota.trim() ? Number(form.monthlyQuota) : null,
      quotaCurrency: form.quotaCurrency || "CNY",
      status: form.status,
      skillIds: form.skillIds,
      mcpServerIds: form.mcpServerIds,
    };
    try {
      let savedId = form.id;
      if (form.id == null) {
        const created = await createAgent(payload);
        savedId = created.id;
        toast.success("已创建 Agent");
      } else {
        await updateAgent(form.id, payload);
        toast.success("已保存（绑定已整体替换）");
      }
      // 按资源授权：白名单单独一次调用（空数组 = 对所有人开放）
      if (savedId != null) {
        await setAgentAllowedUsers(savedId, allowedUserIds);
      }
      setDialogOpen(false);
      await refresh();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败");
    }
  }

  return (
    // h-full + overflow-auto：外层 <main> 是 overflow-hidden，页面必须自己当滚动容器，
    // 否则内容比视口高时会被直接裁掉、滑不到底（MCP 页加了筛选+分页+内置工具后暴露）。
    // 与 AuditPage 保持一致。
    <div className="h-full space-y-4 overflow-auto p-6">
      <div className="flex items-center gap-3">
        <div>
          <h1 className="text-xl font-semibold">Agent 配置</h1>
          <p className="text-muted-foreground text-xs">
            权限模式决定是否走 HITL 审批；agentId 以 <code>demo</code> 开头会额外获得演示卡片工具。
          </p>
        </div>
        <Button
          className="ml-auto"
          onClick={() => {
            setForm({ ...EMPTY, modelId: models[0]?.id ?? null });
            setAllowedUserIds([]);
            setDialogOpen(true);
          }}
        >
          <Plus className="size-4" /> 新建
        </Button>
        <Button variant="outline" onClick={() => void refresh()} disabled={loading}>
          <RefreshCw className="size-4" /> 刷新
        </Button>
      </div>

      <Card>
        <CardHeader className="p-3">
          <CardTitle className="text-sm">Agent 列表（{agents.length}）</CardTitle>
        </CardHeader>
        <CardContent className="p-3 pt-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>名称</TableHead>
                <TableHead>agentId</TableHead>
                <TableHead>模型</TableHead>
                <TableHead>权限模式</TableHead>
                <TableHead>可用用户</TableHead>
                <TableHead>绑定</TableHead>
                <TableHead>状态</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {agents.map((a) => (
                <TableRow key={a.id}>
                  <TableCell className="font-medium">{a.name}</TableCell>
                  <TableCell className="font-mono text-xs">{a.agentId}</TableCell>
                  <TableCell className="text-xs">
                    {models.find((m) => m.id === a.modelId)?.modelId ?? a.modelId ?? "-"}
                  </TableCell>
                  <TableCell>
                    <Badge
                      variant={a.permissionMode === "REQUIRE_APPROVAL" ? "destructive" : "outline"}
                    >
                      {MODE_LABEL[a.permissionMode] ?? a.permissionMode}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-xs" data-testid="agent-allowed-users">
                    {(a.allowedUserIds?.length ?? 0) === 0
                      ? "全部用户"
                      : `${a.allowedUserIds!.length} 个用户`}
                  </TableCell>
                  <TableCell className="text-xs">
                    Skill {a.skillIds.length} · MCP {a.mcpServerIds.length}
                  </TableCell>
                  <TableCell>
                    <Badge variant={a.status === "ENABLED" ? "secondary" : "outline"}>
                      {a.status}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button
                        size="icon"
                        variant="ghost"
                        title="编辑"
                        onClick={() => {
                          setAllowedUserIds(a.allowedUserIds ?? []);
                          setForm({
                            id: a.id,
                            name: a.name,
                            agentId: a.agentId,
                            sysPrompt: a.sysPrompt ?? "",
                            modelId: a.modelId,
                            temperature: a.temperature != null ? String(a.temperature) : "",
                            maxTokens: a.maxTokens != null ? String(a.maxTokens) : "",
                            permissionMode: a.permissionMode,
                            maxIterations:
                              a.maxIterations != null ? String(a.maxIterations) : "",
                            dailyQuota: a.dailyQuota != null ? String(a.dailyQuota) : "",
                            monthlyQuota: a.monthlyQuota != null ? String(a.monthlyQuota) : "",
                            quotaCurrency: a.quotaCurrency ?? "CNY",
                            status: a.status,
                            skillIds: a.skillIds ?? [],
                            mcpServerIds: a.mcpServerIds ?? [],
                          });
                          setDialogOpen(true);
                        }}
                      >
                        <Pencil className="size-3.5" />
                      </Button>
                      <Button
                        size="icon"
                        variant="ghost"
                        title="删除"
                        onClick={async () => {
                          if (!confirm(`删除 Agent「${a.name}」？`)) return;
                          try {
                            await deleteAgent(a.id);
                            toast.success("已删除");
                            await refresh();
                          } catch (err) {
                            toast.error(err instanceof Error ? err.message : "删除失败");
                          }
                        }}
                      >
                        <Trash2 className="size-3.5" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
              {agents.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} className="text-muted-foreground text-center text-sm">
                    还没有 Agent。需要先有模型。
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-h-[90vh] max-w-3xl overflow-auto">
          <DialogHeader>
            <DialogTitle>{form.id == null ? "新建 Agent" : "编辑 Agent"}</DialogTitle>
          </DialogHeader>
          <div className="grid gap-3">
            <div className="grid grid-cols-2 gap-3">
              <label className="space-y-1 text-sm">
                名称
                <Input
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                agentId（唯一；demo 前缀带演示工具）
                <Input
                  value={form.agentId}
                  onChange={(e) => setForm({ ...form, agentId: e.target.value })}
                  placeholder="my-agent"
                />
              </label>
            </div>

            <label className="space-y-1 text-sm">
              系统提示词
              <textarea
                className="border-input min-h-[100px] w-full rounded-md border bg-transparent p-2 text-sm"
                value={form.sysPrompt}
                onChange={(e) => setForm({ ...form, sysPrompt: e.target.value })}
              />
            </label>

            <div className="grid grid-cols-2 gap-3">
              <label className="space-y-1 text-sm">
                模型
                <select
                  className="border-input h-9 w-full rounded-md border bg-transparent px-2 text-sm"
                  value={form.modelId ?? ""}
                  onChange={(e) => setForm({ ...form, modelId: Number(e.target.value) })}
                >
                  {models.map((m) => (
                    <option key={m.id} value={m.id}>
                      {m.displayName ?? m.modelId}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                权限模式
                <select
                  className="border-input h-9 w-full rounded-md border bg-transparent px-2 text-sm"
                  value={form.permissionMode}
                  onChange={(e) =>
                    setForm({ ...form, permissionMode: e.target.value as PermissionMode })
                  }
                >
                  {PERMISSION_MODES.map((m) => (
                    <option key={m} value={m}>
                      {MODE_LABEL[m]}
                    </option>
                  ))}
                </select>
              </label>
            </div>

            <div className="grid grid-cols-3 gap-3">
              <label className="space-y-1 text-sm">
                temperature
                <Input
                  value={form.temperature}
                  onChange={(e) => setForm({ ...form, temperature: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                最大输出 tokens（max_tokens）
                <Input
                  value={form.maxTokens}
                  onChange={(e) => setForm({ ...form, maxTokens: e.target.value })}
                  placeholder="留空=用模型默认"
                />
              </label>
              <label className="space-y-1 text-sm">
                最大迭代步数（maxIterations）
                <Input
                  type="number"
                  min={1}
                  max={200}
                  value={form.maxIterations}
                  onChange={(e) => setForm({ ...form, maxIterations: e.target.value })}
                  placeholder="留空=默认 100"
                />
                <span className="text-muted-foreground block text-xs">
                  一轮对话里最多允许「模型思考 → 调工具 → 看结果」循环几次。
                  技能 / 命令执行类任务很吃步数（只读几份文档就能吃掉一半），建议 ≥ 30（默认 100）；
                  纯问答 5~10 够用。用满时会中断本轮并返回已完成的部分。
                </span>
              </label>
            </div>
            {form.maxIterations.trim() && Number(form.maxIterations) < 10 && (
              <p className="rounded-md bg-amber-50 p-2 text-xs text-amber-700">
                最大迭代步数只有 {form.maxIterations} 步：技能 / 命令执行类任务很可能中途就"步数用尽"。
                建议设为 30 以上（上限 200）。
              </p>
            )}
            {maxTokensWarning(form.maxTokens) && (
              <p className="rounded-md bg-amber-50 p-2 text-xs text-amber-700">
                ⚠️ {maxTokensWarning(form.maxTokens)}
              </p>
            )}

            <div className="grid grid-cols-3 gap-3">
              <label className="space-y-1 text-sm">
                日额度
                <Input
                  data-testid="agent-daily-quota"
                  value={form.dailyQuota}
                  onChange={(e) => setForm({ ...form, dailyQuota: e.target.value })}
                  placeholder="留空=不限"
                />
              </label>
              <label className="space-y-1 text-sm">
                月额度
                <Input
                  data-testid="agent-monthly-quota"
                  value={form.monthlyQuota}
                  onChange={(e) => setForm({ ...form, monthlyQuota: e.target.value })}
                  placeholder="留空=不限"
                />
              </label>
              <label className="space-y-1 text-sm">
                额度货币
                <select
                  className="border-input h-9 rounded-md border px-2 text-sm"
                  data-testid="agent-quota-currency"
                  value={form.quotaCurrency}
                  onChange={(e) => setForm({ ...form, quotaCurrency: e.target.value })}
                >
                  <option value="CNY">CNY</option>
                  <option value="USD">USD</option>
                </select>
              </label>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1 text-sm">
                绑定 Skill
                <div className="border-input max-h-40 overflow-auto rounded-md border p-2 text-xs">
                  {skills.length === 0 && <span className="text-muted-foreground">暂无 Skill</span>}
                  {skills.map((s) => (
                    <label key={s.id} className="flex items-center gap-2 py-0.5">
                      <input
                        type="checkbox"
                        checked={form.skillIds.includes(s.id)}
                        onChange={() =>
                          setForm({ ...form, skillIds: toggle(form.skillIds, s.id) })
                        }
                      />
                      <span>{s.name}</span>
                      {s.status !== "ENABLED" && (
                        <span className="text-muted-foreground">(已禁用)</span>
                      )}
                    </label>
                  ))}
                </div>
              </div>
              <div className="space-y-1 text-sm">
                绑定 MCP Server
                <div className="border-input max-h-40 overflow-auto rounded-md border p-2 text-xs">
                  {servers.length === 0 && (
                    <span className="text-muted-foreground">暂无 MCP Server</span>
                  )}
                  {servers.map((s) => (
                    <label key={s.id} className="flex items-center gap-2 py-0.5">
                      <input
                        type="checkbox"
                        checked={form.mcpServerIds.includes(s.id)}
                        onChange={() =>
                          setForm({ ...form, mcpServerIds: toggle(form.mcpServerIds, s.id) })
                        }
                      />
                      <span>{s.name}</span>
                      <span className="text-muted-foreground">({s.transport})</span>
                    </label>
                  ))}
                </div>
              </div>
            </div>

            <label className="space-y-1 text-sm">
              状态
              <select
                className="border-input h-9 w-full rounded-md border bg-transparent px-2 text-sm"
                value={form.status}
                onChange={(e) =>
                  setForm({ ...form, status: e.target.value as "ENABLED" | "DISABLED" })
                }
              >
                <option value="ENABLED">ENABLED</option>
                <option value="DISABLED">DISABLED</option>
              </select>
            </label>
          </div>
          {/* 按资源授权：不勾选 = 对所有人开放；勾了就只给这些人用 */}
          <div className="space-y-1 text-sm">
            可用用户（不勾选 = 对所有人开放）
            <div
              data-testid="agent-allowed-users-picker"
              className="border-input max-h-32 space-y-1 overflow-auto rounded-md border p-2"
            >
              {users.length === 0 && (
                <p className="text-muted-foreground text-xs">暂无其它用户</p>
              )}
              {users.map((u) => (
                <label key={u.id} className="flex items-center gap-2 text-xs">
                  <input
                    type="checkbox"
                    className="size-3.5"
                    data-testid={`agent-allowed-user-${u.id}`}
                    checked={allowedUserIds.includes(u.id)}
                    onChange={() =>
                      setAllowedUserIds((prev) =>
                        prev.includes(u.id) ? prev.filter((x) => x !== u.id) : [...prev, u.id],
                      )
                    }
                  />
                  {u.displayName ?? u.username}
                  <span className="text-muted-foreground">({u.username})</span>
                </label>
              ))}
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void save()}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
