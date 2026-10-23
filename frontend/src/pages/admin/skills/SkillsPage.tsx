import { useCallback, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import {
  Plus,
  RefreshCw,
  Trash2,
  Pencil,
  Sparkles,
  Download,
  PackagePlus,
  FolderTree,
  FileText,
} from "lucide-react";
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
  listSkillResources,
  listSkills,
  readSkillResource,
  updateSkill,
  type SkillImportCandidate,
  type SkillInfo,
  type SkillResourceInfo,
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
  /** 非空 = 从仓库导入的技能：内容只读，只能改启用状态或按来源更新。 */
  sourceUrl: string | null;
}

const EMPTY: FormState = {
  id: null,
  name: "",
  description: "",
  content: "",
  status: "ENABLED",
  version: null,
  sourceUrl: null,
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
  const [importNotes, setImportNotes] = useState<string[]>([]);

  // 渐进式披露的第二层：资源文件（references/ scripts/ data/ …）
  const [resources, setResources] = useState<SkillResourceInfo[]>([]);
  const [resourcesOpen, setResourcesOpen] = useState(false);
  const [resourcesLoading, setResourcesLoading] = useState(false);
  const [openedResource, setOpenedResource] = useState<SkillResourceInfo | null>(null);
  /** 「按来源更新」进行中（导入的技能不能手改，只能从仓库重新拉）。 */
  const [reimportBusy, setReimportBusy] = useState(false);
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
      setImportNotes(result.notes ?? []);
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
        // 关键提醒：装完但没绑定 Agent，模型在对话里看不到它（用户实测踩过）
        const hint = (result.notes ?? []).find((note) => note.includes("还没绑定"));
        if (hint) {
          toast.warning(hint, { duration: 8000 });
        }
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

  /** 导入的技能：按来源仓库重新拉一遍（内容只读，这是唯一的更新途径）。 */
  async function reimportFromSource() {
    if (!form.sourceUrl || form.id == null) {
      return;
    }
    setReimportBusy(true);
    try {
      const result = await importSkills({
        url: form.sourceUrl,
        preview: false,
        names: [form.name],
        overwrite: true,
        // 不改启用状态：是否启用是平台本地开关，不属于仓库内容
        enable: false,
      });
      const installed = result.installed[0];
      if (!installed) {
        toast.error(result.skipped[0]?.reason ?? "没有更新任何内容");
        return;
      }
      toast.success(
        installed.action === "unchanged"
          ? "仓库内容没有变化"
          : `已按来源更新到 v${installed.version}（${installed.resourceCount} 个资源文件）`,
      );
      setDialogOpen(false);
      await refresh();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "按来源更新失败");
    } finally {
      setReimportBusy(false);
    }
  }

  /** 拉取该技能的资源清单（只含路径与大小）。 */
  async function loadResources(skillId: number) {
    setResources([]);
    setOpenedResource(null);
    setResourcesOpen(false);
    setResourcesLoading(true);
    try {
      const list = await listSkillResources(skillId);
      setResources(list);
      setResourcesOpen(list.length > 0);
    } catch {
      setResources([]);
    } finally {
      setResourcesLoading(false);
    }
  }

  /** 点开一个资源文件才读正文（大型技能有几十个文件，不预读）。 */
  async function openResource(skillId: number, path: string) {
    try {
      setOpenedResource(await readSkillResource(skillId, path));
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "读取资源失败");
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
    // 导入的技能只允许改状态：名称/说明/正文必须**原样回传**（后端会比对，
    // 一旦发现内容变了就 409 —— 免得"改了但下次导入被覆盖"）。
    const payload = form.sourceUrl
      ? {
          name: form.name,
          description: form.description,
          content: form.content,
          status: form.status,
        }
      : {
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
            setResources([]);
            setOpenedResource(null);
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
                      {s.status === "ENABLED" && (s.boundAgentCount ?? 0) === 0 && (
                        <Badge
                          variant="outline"
                          className="border-amber-400 text-amber-700"
                          title="没有 Agent 绑定它：模型在对话里看不到这个技能（输入框的 / 候选也只列绑定的）。到「Agent 配置」里绑定"
                          data-testid="skill-unbound"
                        >
                          未绑定 Agent
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
                            sourceUrl: s.sourceUrl ?? null,
                          });
                          setDialogOpen(true);
                          void loadResources(s.id);
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
            {/* 外部导入的技能：内容只读。手改会在下次导入时被覆盖，所以干脆不让改。 */}
            {form.sourceUrl && (
              <div
                className="rounded-md border border-amber-300 bg-amber-50 p-3 text-xs text-amber-900"
                data-testid="skill-imported-banner"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <b>该技能由仓库导入，内容只读。</b>
                  <span className="text-amber-800">要改请改仓库，然后</span>
                  <Button
                    size="sm"
                    variant="outline"
                    className="h-6 px-2 text-xs"
                    data-testid="skill-reimport"
                    disabled={reimportBusy}
                    onClick={() => void reimportFromSource()}
                  >
                    <Download className="mr-1 size-3" />
                    {reimportBusy ? "更新中…" : "按来源更新"}
                  </Button>
                  <span className="text-amber-800">；这里只能切换启用状态。</span>
                </div>
                <div className="mt-1 truncate font-mono text-[11px] text-amber-700">
                  {form.sourceUrl}
                </div>
              </div>
            )}
            {/* 用 AI 生成：把自然语言需求 + 可用工具清单交给模型，产出草稿再人工确认 */}
            {!form.sourceUrl && (
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
            )}
            <div className="grid grid-cols-2 gap-3">
              <label className="space-y-1 text-sm">
                名称 <span className="text-destructive">*</span>
                <Input
                  value={form.name}
                  disabled={!!form.sourceUrl}
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
              <textarea
                data-testid="skill-description"
                className="border-input min-h-[60px] w-full resize-y rounded-md border bg-transparent p-2 text-sm disabled:opacity-70"
                disabled={!!form.sourceUrl}
                value={form.description}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
                placeholder="例如：需要用户确认危险操作时弹确认卡"
              />
            </label>

            {/*
              渐进式披露：资源文件是技能的第二层。以前这里只显示 SKILL.md 正文，
              用户会以为「导入只进来一个 markdown」——其实 references/ scripts/ data/ 都在库里。
            */}
            {form.id != null && (resources.length > 0 || resourcesLoading) && (
              <div className="rounded-md border" data-testid="skill-resources">
                <button
                  type="button"
                  className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm font-medium"
                  onClick={() => setResourcesOpen((open) => !open)}
                >
                  <FolderTree className="size-4" />
                  资源文件（{resources.length}）
                  <span className="text-muted-foreground text-xs font-normal">
                    模型按需用 load_skill_through_path 读取，不占上下文
                  </span>
                  <span className="ml-auto text-xs">{resourcesOpen ? "收起" : "展开"}</span>
                </button>
                {resourcesOpen && (
                  <div className="max-h-[240px] overflow-auto border-t px-3 py-2">
                    <ul className="space-y-0.5 text-xs">
                      {resources.map((resource) => (
                        <li key={resource.path}>
                          <button
                            type="button"
                            className="hover:bg-muted flex w-full items-center gap-2 rounded px-1 py-0.5 text-left"
                            onClick={() => void openResource(form.id as number, resource.path)}
                          >
                            <FileText className="size-3 shrink-0" />
                            <span className="truncate font-mono">{resource.path}</span>
                            <span className="text-muted-foreground ml-auto shrink-0">
                              {resource.binary ? "二进制" : `${resource.size} 字`}
                            </span>
                          </button>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
                {openedResource && (
                  <div className="border-t px-3 py-2">
                    <div className="mb-1 flex items-center gap-2 text-xs">
                      <span className="truncate font-mono">{openedResource.path}</span>
                      <Button
                        size="sm"
                        variant="ghost"
                        className="ml-auto h-6 px-2 text-xs"
                        onClick={() => setOpenedResource(null)}
                      >
                        关闭
                      </Button>
                    </div>
                    <pre className="bg-muted/40 max-h-[200px] overflow-auto whitespace-pre-wrap rounded p-2 text-xs">
                      {openedResource.binary
                        ? "（二进制文件，此处不展示内容）"
                        : (openedResource.content ?? "")}
                    </pre>
                  </div>
                )}
              </div>
            )}

            <div className="flex min-h-0 flex-1 flex-col">
              {form.sourceUrl ? (
                <div className="flex min-h-0 flex-1 flex-col space-y-1">
                  <span className="text-muted-foreground text-xs">
                    SKILL.md 正文（只读；模型按需用 load_skill_through_path 加载）
                  </span>
                  <pre
                    data-testid="skill-content-readonly"
                    className="bg-muted/40 min-h-[240px] flex-1 overflow-auto whitespace-pre-wrap rounded-md border p-3 text-xs"
                  >
                    {form.content}
                  </pre>
                </div>
              ) : (
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
              )}
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>
              取消
            </Button>
            <Button data-testid="skill-save" onClick={() => void save()}>
              {form.sourceUrl ? "保存状态" : "保存"}
            </Button>
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
                {importNotes.length > 0 && (
                  <ul className="text-muted-foreground list-inside list-disc space-y-0.5 text-xs">
                    {importNotes.map((note) => (
                      <li key={note}>{note}</li>
                    ))}
                  </ul>
                )}
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
