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

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const page = await listSkills();
      setSkills(page.records);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载 Skill 列表失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  async function save() {
    if (!form.name.trim()) {
      toast.error("名称不能为空");
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
    <div className="space-y-4 p-6">
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
          <CardTitle className="text-sm">Skill 列表（{skills.length}）</CardTitle>
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
        </CardContent>
      </Card>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-w-3xl">
          <DialogHeader>
            <DialogTitle>
              {form.id == null ? "新建 Skill" : `编辑 Skill（当前 v${form.version}）`}
            </DialogTitle>
          </DialogHeader>
          <div className="grid gap-3">
            <div className="grid grid-cols-2 gap-3">
              <label className="space-y-1 text-sm">
                名称
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
              说明
              <Input
                value={form.description}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
                placeholder="一句话说明这个 Skill 做什么"
              />
            </label>
            <label className="space-y-1 text-sm">
              内容（Markdown）
              <textarea
                className="border-input min-h-[260px] w-full rounded-md border bg-transparent p-2 font-mono text-xs"
                value={form.content}
                onChange={(e) => setForm({ ...form, content: e.target.value })}
                placeholder={"# 规则\n\n回答时在末尾加【OK】。"}
              />
            </label>
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
