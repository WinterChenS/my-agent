import { useCallback, useEffect, useState } from "react";
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
  createSkill,
  deleteSkill,
  listSkills,
  updateSkill,
  type SkillInfo,
} from "@/api/skills";
import { listTools, type ToolEntry } from "@/api/tools";
import { cn } from "@/lib/utils";
import { SkillMdxEditor } from "@/components/skills/SkillMdxEditor";

interface FormState {
  id: number | null;
  name: string;
  description: string;
  content: string;
  status: "ENABLED" | "DISABLED";
  version: number | null;
}

const EMPTY: FormState = {
  id: null,
  name: "",
  description: "",
  content: "",
  status: "ENABLED",
  version: null,
};

export default function SkillsPage() {
  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY);
  // 分页（技能多起来后一页铺完既卡又难找）
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [total, setTotal] = useState(0);

  /** 可插入的工具（MCP + 本地）：编辑器里输入 `/` 用。加载一次就够。 */
  const [tools, setTools] = useState<ToolEntry[]>([]);

  useEffect(() => {
    void (async () => {
      try {
        setTools(await listTools());
      } catch {
        setTools([]);
      }
    })();
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listSkills(page, pageSize);
      setSkills(result.records);
      setTotal(result.total);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载 Skill 列表失败");
    } finally {
      setLoading(false);
    }
  }, [page, pageSize]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  async function save() {
    if (!form.name.trim()) {
      toast.error("名称不能为空");
      return;
    }
    // 官方技能仓库要求 description 非空（否则同步时报错），这里提前拦住
    if (!form.description.trim()) {
      toast.error("说明不能为空：它决定模型何时命中这个技能");
      return;
    }
    const payload = {
      name: form.name.trim(),
      description: form.description.trim() || null,
      content: form.content,
      status: form.status,
    };
    try {
      if (form.id == null) {
        await createSkill(payload);
        toast.success("已创建 Skill");
      } else {
        const saved = await updateSkill(form.id, payload);
        toast.success(`已保存（版本 v${saved.version}）`);
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
          <h1 className="text-xl font-semibold">Skill 配置</h1>
          <p className="text-muted-foreground text-xs">
            内容为 Markdown；内容变化时版本自动 +1。启用的 Skill 会同步到 Agent 运行时仓库。
          </p>
        </div>
        <Button
          className="ml-auto"
          onClick={() => {
            setForm(EMPTY);
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
          <CardTitle className="text-sm">Skill 列表（{total}）</CardTitle>
        </CardHeader>
        <CardContent className="p-3 pt-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>名称</TableHead>
                <TableHead>说明</TableHead>
                <TableHead>版本</TableHead>
                <TableHead>状态</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {skills.map((s) => (
                <TableRow key={s.id}>
                  <TableCell className="font-medium">{s.name}</TableCell>
                  <TableCell className="max-w-[360px] truncate text-xs">
                    {s.description ?? "-"}
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline">v{s.version}</Badge>
                  </TableCell>
                  <TableCell>
                    <Badge variant={s.status === "ENABLED" ? "secondary" : "outline"}>
                      {s.status}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button
                        size="icon"
                        variant="ghost"
                        title="编辑"
                        onClick={() => {
                          setForm({
                            id: s.id,
                            name: s.name,
                            description: s.description ?? "",
                            content: s.content ?? "",
                            status: s.status,
                            version: s.version,
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
                          if (!confirm(`删除 Skill「${s.name}」？（被 Agent 绑定时会被拒绝）`)) return;
                          try {
                            await deleteSkill(s.id);
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
              {skills.length === 0 && (
                <TableRow>
                  <TableCell colSpan={5} className="text-muted-foreground text-center text-sm">
                    还没有 Skill。
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>

          {/* 分页 */}
          <div className="mt-3 flex flex-wrap items-center justify-between gap-2 text-sm" data-testid="skills-pagination">
            <div className="text-muted-foreground text-xs">
              共 {total} 条，第 {page} / {Math.max(1, Math.ceil(total / pageSize))} 页
            </div>
            <div className="flex items-center gap-2">
              <label className="text-muted-foreground text-xs">
                每页
                <select
                  className="border-input ml-1 h-7 rounded-md border px-1 text-xs"
                  data-testid="skills-page-size"
                  value={pageSize}
                  onChange={(e) => {
                    setPageSize(Number(e.target.value));
                    setPage(1);
                  }}
                >
                  {[10, 20, 50].map((n) => (
                    <option key={n} value={n}>
                      {n}
                    </option>
                  ))}
                </select>
              </label>
              <Button
                variant="outline"
                size="sm"
                data-testid="skills-prev"
                disabled={page <= 1 || loading}
                onClick={() => setPage((p) => Math.max(1, p - 1))}
              >
                上一页
              </Button>
              <Button
                variant="outline"
                size="sm"
                data-testid="skills-next"
                disabled={page >= Math.ceil(total / pageSize) || loading}
                onClick={() => setPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent
          data-testid="skill-dialog"
          className={cn(
            // 技能正文是要写 Markdown 的，弹窗必须够大：
            // 默认 DialogContent 带 sm:max-w-lg（512px）且无高度约束，双栏会挤成一团。
            // 这里显式给出「宽 + 高」，并用 grid-rows 让中间的表单区吃掉剩余高度。
            "h-[88vh] max-w-[95vw] grid-rows-[auto_minmax(0,1fr)_auto] overflow-hidden",
            "sm:max-w-[95vw] lg:max-w-[88vw] xl:max-w-7xl",
          )}
        >
          <DialogHeader>
            <DialogTitle>
              {form.id == null ? "新建 Skill" : `编辑 Skill（当前 v${form.version}）`}
            </DialogTitle>
          </DialogHeader>
          <div className="flex min-h-0 flex-col gap-3 overflow-y-auto">
            <div className="grid grid-cols-2 gap-3">
              <label className="space-y-1 text-sm">
                名称 <span className="text-destructive">*</span>
                <Input
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="reply-with-ok"
                />
              </label>
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
            <label className="space-y-1 text-sm">
              说明 <span className="text-destructive">*</span>
              <span className="text-muted-foreground ml-1 text-xs">
                必填：模型靠它判断什么时候用这个技能
              </span>
              <Input
                data-testid="skill-description"
                value={form.description}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
                placeholder="例如：需要用户确认危险操作时弹确认卡"
              />
            </label>
            <div className="flex min-h-0 flex-1 flex-col">
              <SkillMdxEditor
                value={form.content}
                onChange={(next) => setForm({ ...form, content: next })}
                tools={tools}
                placeholder="# 规则&#10;&#10;回答时在末尾加【OK】。"
              />
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
