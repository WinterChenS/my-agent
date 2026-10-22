import { useCallback, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { Plus, RefreshCw, Trash2, Pencil, Sparkles, Download, PackagePlus } from "lucide-react";
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
  generateSkill,
  importSkills,
  listSkills,
  updateSkill,
  type SkillImportCandidate,
  type SkillInfo,
} from "@/api/skills";
import { listTools, type ToolEntry } from "@/api/tools";
import { listModels, type ModelInfo } from "@/api/models";
import { cn } from "@/lib/utils";
import { Suspense, lazy } from "react";

/**
 * 所见即所得编辑器（内含整个 MDXEditor / Lexical，体积很大）**按需加载**：
 * 只有打开「新建/编辑技能」弹窗时才下载，光看技能列表不必付这个代价（实测该 chunk 约 1.2MB）。
 */
const SkillMdxEditor = lazy(() =>
  import("@/components/skills/SkillMdxEditor").then((m) => ({ default: m.SkillMdxEditor })),
);
type SkillMdxEditorHandle = import("@/components/skills/SkillMdxEditor").SkillMdxEditorHandle;

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
  // 「用 AI 生成」面板的状态
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [aiModelId, setAiModelId] = useState<number | null>(null);
  const [aiRequirement, setAiRequirement] = useState("");
  const [aiBusy, setAiBusy] = useState(false);
  const [aiNotice, setAiNotice] = useState<string | null>(null);
  /** 编辑器句柄：MDXEditor 的 markdown 只当初始值，AI 生成后要用 setMarkdown 回填。 */
  const editorHandle = useRef<SkillMdxEditorHandle | null>(null);

  // 「从仓库导入」弹窗：先预览仓库里有哪些技能，再挑要装的
  const [importOpen, setImportOpen] = useState(false);
  const [importUrl, setImportUrl] = useState("");
  const [importRef, setImportRef] = useState("");
  const [importBusy, setImportBusy] = useState(false);
  const [importCandidates, setImportCandidates] = useState<SkillImportCandidate[]>([]);
  const [importSelected, setImportSelected] = useState<string[]>([]);
  const [importEnable, setImportEnable] = useState(false);
  const [importForce, setImportForce] = useState(false);
  const [importSource, setImportSource] = useState<{ url: string; ref: string } | null>(null);
  /** 候选里出现的技能包（去重后，用于「只选某包」快捷键）。 */
  const importPacks = Array.from(
    new Set(importCandidates.map((c) => c.pack).filter((pack): pack is string => !!pack)),
  );

  useEffect(() => {
    void (async () => {
      try {
        setTools(await listTools());
      } catch {
        setTools([]);
      }
      try {
        const page = await listModels(1, 200);
        const enabled = page.records.filter((m) => m.status === "ENABLED");
        setModels(enabled);
        setAiModelId((current) => current ?? enabled[0]?.id ?? null);
      } catch {
        setModels([]);
      }
    })();
  }, []);

  /** 调后端让 AI 按需求生成技能草稿，并填入表单。 */
  async function runAiGenerate() {
    if (!aiRequirement.trim()) {
      toast.error("先用一句话说说你想要什么技能");
      return;
    }
    setAiBusy(true);
    setAiNotice(null);
    try {
      const draft = await generateSkill(aiRequirement.trim(), aiModelId);
      setForm((prev) => ({
        ...prev,
        name: draft.name,
        description: draft.description,
        content: draft.content,
      }));
      // 关键：编辑器不会因为 state 变化而更新，必须显式把正文塞进去（实测过的坑）
      editorHandle.current?.setMarkdown(draft.content);
      toast.success("已生成，请检查后保存");
      if (draft.notice) {
        setAiNotice(draft.notice);
      }
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "生成失败");
    } finally {
      setAiBusy(false);
    }
  }

  /** 预览仓库：只下载 + 拆包 + 扫描，不落库。 */
  async function runImportPreview() {
    if (!importUrl.trim()) {
      toast.error("先填仓库链接");
      return;
    }
    setImportBusy(true);
    setImportCandidates([]);
    setImportSelected([]);
    try {
      const result = await importSkills({
        url: importUrl.trim(),
        ref: importRef.trim() || null,
        preview: true,
      });
      setImportCandidates(result.candidates);
      setImportSource({ url: result.downloadUrl, ref: result.ref });
      // 默认勾选：安全的、且不是「已存在」的（已存在的必须由人明确勾）
      setImportSelected(
        result.candidates
          .filter((c) => c.verdict !== "DANGEROUS" && c.existingSkillId == null)
          .map((c) => c.root),
      );
      if (result.candidates.length === 0) {
        toast.error("仓库里没有找到 SKILL.md");
      }
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "预览失败");
    } finally {
      setImportBusy(false);
    }
  }

  /** 安装选中的技能（同一批，后端一个事务）。 */
  async function runImportInstall() {
    if (importSelected.length === 0) {
      toast.error("先勾选要安装的技能");
      return;
    }
    setImportBusy(true);
    try {
      const result = await importSkills({
        url: importUrl.trim(),
        ref: importRef.trim() || null,
        preview: false,
        names: importSelected,
        overwrite: true,
        enable: importEnable,
        force: importForce,
      });
      const installed = result.installed.length;
      const skipped = result.skipped.length;
      if (installed > 0) {
        toast.success(`已安装 ${installed} 个技能${skipped > 0 ? `，跳过 ${skipped} 个` : ""}`);
      } else {
        toast.error(
          skipped > 0 ? result.skipped.map((s) => s.reason).join("；") : "没有安装任何技能",
        );
      }
      setImportOpen(false);
      setImportCandidates([]);
      await refresh();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "安装失败");
    } finally {
      setImportBusy(false);
    }
  }

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
        // 列表按 id 升序 + 分页，新建的条目在最后一页 —— 不跳过去用户会以为没建成功
        setPage(Math.max(1, Math.ceil((total + 1) / pageSize)));
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
          variant="outline"
          data-testid="skill-import-open"
          onClick={() => {
            setImportCandidates([]);
            setImportSelected([]);
            setImportOpen(true);
          }}
        >
          <PackagePlus className="size-4" /> 从仓库导入
        </Button>
        <Button
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
                  <TableCell className="font-medium">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span>{s.name}</span>
                      {(s.resourceCount ?? 0) > 0 && (
                        <Badge variant="outline" title="资源文件数量">
                          {s.resourceCount} 文件
                        </Badge>
                      )}
                      {s.sourceUrl && (
                        <Badge variant="secondary" title={`来源：${s.sourceUrl}`}>
                          <Download className="mr-1 size-3" />
                          仓库
                        </Badge>
                      )}
                    </div>
                  </TableCell>
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
            {/* 用 AI 生成：把自然语言需求 + 可用工具清单交给模型，产出草稿再人工确认 */}
            <div
              className="bg-muted/40 space-y-2 rounded-md border border-dashed p-3"
              data-testid="skill-ai-panel"
            >
              <div className="flex items-center gap-2 text-sm font-medium">
                <Sparkles className="size-4" />
                用 AI 生成
                <span className="text-muted-foreground text-xs font-normal">
                  把可用的工具清单（本地 {tools.filter((t) => t.group.includes("本地")).length} 个 + MCP
                  {tools.filter((t) => !t.group.includes("本地")).length} 个）与技能格式要求一并交给模型
                </span>
              </div>
              <textarea
                data-testid="skill-ai-requirement"
                className="border-input min-h-[64px] w-full resize-y rounded-md border bg-transparent p-2 text-xs"
                placeholder="用一句话描述你想要的技能，例如：当用户问订单物流时，先查订单再回复；查不到就让他确认订单号。"
                value={aiRequirement}
                onChange={(e) => setAiRequirement(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
                    e.preventDefault();
                    void runAiGenerate();
                  }
                }}
              />
              <div className="flex flex-wrap items-center gap-2">
                <label className="text-xs">
                  使用模型
                  <select
                    className="border-input ml-1 h-8 rounded-md border bg-transparent px-2 text-xs"
                    data-testid="skill-ai-model"
                    value={aiModelId ?? ""}
                    onChange={(e) => setAiModelId(e.target.value ? Number(e.target.value) : null)}
                  >
                    {models.length === 0 && <option value="">（没有可用模型）</option>}
                    {models.map((m) => (
                      <option key={m.id} value={m.id}>
                        {m.displayName || m.modelId}
                      </option>
                    ))}
                  </select>
                </label>
                <Button
                  size="sm"
                  data-testid="skill-ai-generate"
                  disabled={aiBusy || models.length === 0}
                  onClick={() => void runAiGenerate()}
                >
                  <Sparkles className="size-3.5" />
                  {aiBusy ? "生成中…" : "生成"}
                </Button>
                <span className="text-muted-foreground text-xs">
                  生成后会自动填入下面的名称 / 说明 / 正文，可以再改（Ctrl+Enter 快捷生成）
                </span>
              </div>
              {aiNotice && (
                <p className="text-xs text-amber-600" data-testid="skill-ai-notice">
                  {aiNotice}
                </p>
              )}
            </div>
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
              <Suspense
                fallback={
                  <div className="text-muted-foreground flex h-[320px] items-center justify-center rounded-md border text-xs">
                    正在加载编辑器…
                  </div>
                }
              >
              <SkillMdxEditor
                ref={editorHandle}
                value={form.content}
                onChange={(next) => setForm({ ...form, content: next })}
                tools={tools}
                placeholder="# 规则&#10;&#10;回答时在末尾加【OK】。"
              />
              </Suspense>
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

      {/* 从仓库导入：预览 → 勾选 → 安装（与聊天里的 install_skill 走同一个后端服务） */}
      <Dialog open={importOpen} onOpenChange={setImportOpen}>
        <DialogContent
          data-testid="skill-import-dialog"
          className="max-h-[88vh] overflow-y-auto sm:max-w-3xl"
        >
          <DialogHeader>
            <DialogTitle>从仓库导入 Skill</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
            <div className="flex flex-wrap items-end gap-2">
              <label className="flex-1 space-y-1 text-sm">
                仓库链接
                <Input
                  data-testid="skill-import-url"
                  value={importUrl}
                  onChange={(e) => setImportUrl(e.target.value)}
                  placeholder="https://github.com/anthropics/skills 或某个 .zip 直链"
                />
              </label>
              <label className="w-36 space-y-1 text-sm">
                分支/标签
                <Input
                  data-testid="skill-import-ref"
                  value={importRef}
                  onChange={(e) => setImportRef(e.target.value)}
                  placeholder="默认分支"
                />
              </label>
              <Button
                variant="outline"
                data-testid="skill-import-preview"
                disabled={importBusy}
                onClick={() => void runImportPreview()}
              >
                {importBusy ? "处理中…" : "预览"}
              </Button>
            </div>
            <p className="text-muted-foreground text-xs">
              支持 GitHub / GitLab 仓库地址（一个仓库可以有多个技能）或 .zip 直链。安装前会做安全扫描，
              危险内容会被拒绝；导入的技能默认<b>停用</b>，确认无误后再启用。
            </p>

            {importCandidates.length > 0 && (
              <div className="space-y-2">
                <div className="text-muted-foreground flex flex-wrap items-center gap-2 text-xs">
                  <span>
                    发现 {importCandidates.length} 个技能
                    {importPacks.length > 0 && `（${importPacks.length} 个技能包）`}
                  </span>
                  {importSource && (
                    <span>
                      ref <code>{importSource.ref}</code>
                    </span>
                  )}
                  {importPacks.map((pack) => {
                    const roots = importCandidates
                      .filter((c) => c.pack === pack && c.verdict !== "DANGEROUS")
                      .map((c) => c.root);
                    return (
                      <Button
                        key={pack}
                        size="sm"
                        variant="outline"
                        className="h-6 px-2 text-xs"
                        onClick={() => setImportSelected(roots)}
                      >
                        只选「{pack}」（{roots.length}）
                      </Button>
                    );
                  })}
                </div>
                <div className="divide-y rounded-md border">
                  {importCandidates.map((c) => {
                    const disabled = c.verdict === "DANGEROUS";
                    const checked = importSelected.includes(c.root);
                    return (
                      <label
                        key={c.root}
                        className={cn(
                          "flex cursor-pointer items-start gap-2 p-2 text-sm",
                          disabled && !importForce && "cursor-not-allowed opacity-60",
                        )}
                      >
                        <input
                          type="checkbox"
                          className="mt-1"
                          disabled={disabled && !importForce}
                          checked={checked}
                          onChange={(e) =>
                            setImportSelected((prev) =>
                              e.target.checked
                                ? [...prev, c.root]
                                : prev.filter((root) => root !== c.root),
                            )
                          }
                        />
                        <div className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-center gap-1.5">
                            <span className="font-medium">{c.name}</span>
                            <Badge variant="outline">{c.root || "(根目录)"}</Badge>
                            {c.pack && <Badge variant="secondary">包 · {c.pack}</Badge>}
                            <Badge variant="outline">{c.resourceCount} 文件</Badge>
                            <Badge variant={c.verdict === "SAFE" ? "secondary" : "destructive"}>
                              {c.verdict}
                            </Badge>
                            {c.existingSkillId != null && (
                              <Badge variant="outline">已存在 v{c.existingVersion}，将更新</Badge>
                            )}
                          </div>
                          <p className="text-muted-foreground mt-0.5 line-clamp-2 text-xs">
                            {c.description}
                          </p>
                          {c.findings.length > 0 && (
                            <ul className="mt-1 list-inside list-disc text-xs text-amber-600">
                              {c.findings.slice(0, 3).map((f) => (
                                <li key={f}>{f}</li>
                              ))}
                            </ul>
                          )}
                        </div>
                      </label>
                    );
                  })}
                </div>
                <div className="flex flex-wrap items-center gap-4">
                  <label className="flex items-center gap-2 text-xs">
                    <input
                      type="checkbox"
                      data-testid="skill-import-enable"
                      checked={importEnable}
                      onChange={(e) => setImportEnable(e.target.checked)}
                    />
                    安装后立即启用（默认停用，人工确认后再启用）
                  </label>
                  {importCandidates.some((c) => c.verdict === "DANGEROUS") && (
                    <label className="flex items-center gap-2 text-xs text-amber-600">
                      <input
                        type="checkbox"
                        data-testid="skill-import-force"
                        checked={importForce}
                        onChange={(e) => setImportForce(e.target.checked)}
                      />
                      允许安装被判定为 DANGEROUS 的技能（我已看过上面的命中项）
                    </label>
                  )}
                </div>
              </div>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setImportOpen(false)}>
              取消
            </Button>
            <Button
              data-testid="skill-import-install"
              disabled={importBusy || importSelected.length === 0}
              onClick={() => void runImportInstall()}
            >
              安装选中（{importSelected.length}）
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
