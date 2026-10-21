import { useCallback, useEffect, useMemo, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { toast } from "sonner";
import { Brain, Eye, FileText, Pencil, RefreshCw, Save, Trash2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatSize } from "@/api/artifacts";
import {
  deleteMemoryFile,
  getMemoryFile,
  listMemoryFiles,
  saveMemoryFile,
  type MemoryFileInfo,
} from "@/api/memory";
import type { UserRow } from "@/api/users";
import { MemoryUserSearch } from "@/components/memory/MemoryUserSearch";
import { useAuthStore } from "@/stores/auth";
import { fileLabel, isDirty, sortMemoryFiles, summarizeMemory } from "@/lib/memory/files";

/**
 * 记忆可视化（可编辑）。
 *
 * <p>把 Agent 的长期记忆摊开给人看：左边是清单（`MEMORY.md` + 每日台账），右边可以**预览**或**直接编辑**。
 * 保存走 `PUT /api/memory/{name}`，改完**下一轮对话立即生效**（框架每轮都会重新读记忆文件；
 * 平台还有一个中间件把关键条目提到系统提示最前面）。
 *
 * <p>注意：`MEMORY.md` 是框架 consolidation 合并出来的“精华”，人工改完它不会阻止后续合并；
 * 每日台账是追加写入的账本，删掉某天的文件就等于删掉那天的原始记录。
 *
 * <p>谁能看谁的记忆：**默认就是自己**。`ADMIN` 另外有一个搜索框，按**用户名或用户 ID**
 * 精确找到别人（排查“Agent 记了错东西”时需要看对方到底记了什么）；普通用户没有这个框，
 * 后端也会把“传别人 id”的请求 403 掉。
 */
export default function MemoryPage() {
  const currentUser = useAuthStore((state) => state.user);
  const isAdmin = currentUser?.role === "ADMIN";
  /** 查看谁的记忆；null = 自己（ADMIN 才会切成别人） */
  const [viewUser, setViewUser] = useState<UserRow | null>(null);
  const viewUserId = viewUser?.id ?? null;
  const [files, setFiles] = useState<MemoryFileInfo[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  /** 已加载的正文（服务器上的版本） */
  const [original, setOriginal] = useState("");
  /** 编辑中的草稿 */
  const [draft, setDraft] = useState("");
  const [mode, setMode] = useState<"preview" | "edit">("preview");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const summary = useMemo(() => summarizeMemory(files), [files]);
  const current = files.find((file) => file.name === selected) ?? null;
  const dirty = isDirty(original, draft);

  const loadList = useCallback(async () => {
    try {
      const list = sortMemoryFiles(await listMemoryFiles(viewUserId));
      setFiles(list);
      // 换了用户 / 刷新之后，尽量留在原来那份上；那份没了就退回第一份
      setSelected((prev) =>
        prev && list.some((file) => file.name === prev) ? prev : (list[0]?.name ?? null),
      );
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载记忆清单失败");
    }
  }, [viewUserId]);

  useEffect(() => {
    void loadList();
  }, [loadList]);

  // 选中哪份就读哪份（草稿重置为服务器版本）
  useEffect(() => {
    if (!selected) {
      setOriginal("");
      setDraft("");
      return;
    }
    let cancelled = false;
    setLoading(true);
    getMemoryFile(selected, viewUserId)
      .then((doc) => {
        if (cancelled) return;
        const content = doc.content ?? "";
        setOriginal(content);
        setDraft(content);
        setFiles((prev) =>
          prev.map((file) =>
            file.name === doc.name
              ? { ...file, sizeBytes: doc.sizeBytes, updatedAt: doc.updatedAt }
              : file,
          ),
        );
      })
      .catch((err: unknown) => {
        if (!cancelled) toast.error(err instanceof Error ? err.message : "读取失败");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // viewUserId 也要在依赖里：换了用户之后哪怕选中的还是同名文件，正文也得重新取
  }, [selected, viewUserId]);

  /** 切换文件：有未保存改动先问一句（避免静默丢编辑）。 */
  function selectFile(name: string) {
    if (name === selected) {
      return;
    }
    if (dirty && !window.confirm("当前修改还没保存，确定切换吗？")) {
      return;
    }
    setSelected(name);
  }

  /** 切换用户（仅 ADMIN）：同样先问一句，然后清空选中，让清单重新加载。 */
  function switchUser(next: UserRow | null) {
    if ((next?.id ?? null) === viewUserId) {
      return;
    }
    if (dirty && !window.confirm("当前修改还没保存，确定切换到其他用户吗？")) {
      return;
    }
    setViewUser(next);
    setSelected(null);
    setOriginal("");
    setDraft("");
  }

  async function save() {
    if (!selected || saving) {
      return;
    }
    setSaving(true);
    try {
      const saved = await saveMemoryFile(selected, draft, viewUserId);
      setOriginal(draft);
      setFiles((prev) =>
        prev.map((file) =>
          file.name === saved.name
            ? { ...file, sizeBytes: saved.sizeBytes, updatedAt: saved.updatedAt }
            : file,
        ),
      );
      toast.success("已保存（下一轮对话即刻生效）");
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败");
    } finally {
      setSaving(false);
    }
  }

  async function remove() {
    if (!selected || current?.kind === "CURATED") {
      return;
    }
    if (!window.confirm(`删除 ${selected}？这天的原始记忆记录会被移除。`)) {
      return;
    }
    try {
      await deleteMemoryFile(selected, viewUserId);
      toast.success("已删除");
      setSelected(null);
      setOriginal("");
      setDraft("");
      await loadList();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "删除失败");
    }
  }

  return (
    <div className="flex h-full flex-col" data-testid="memory-page">
      <header className="flex flex-wrap items-center gap-2 border-b p-4">
        <Brain className="size-5" />
        <h1 className="text-lg font-semibold">记忆</h1>
        <Badge variant="secondary" data-testid="memory-summary">
          {summary.count} 份 · {formatSize(summary.bytes)}
        </Badge>
        <span className="text-muted-foreground text-xs">
          长期记忆与每日台账，可直接编辑；保存后下一轮对话立即生效
        </span>
        <div className="ml-auto flex items-center gap-2">
          {isAdmin && <MemoryUserSearch selected={viewUser} onSelect={switchUser} />}
          <Button
            variant="ghost"
            size="icon-sm"
            title="刷新"
            data-testid="memory-refresh"
            onClick={() => void loadList()}
          >
            <RefreshCw className="size-3.5" />
          </Button>
        </div>
      </header>

      <div className="flex min-h-0 flex-1">
        {/* 左：清单 */}
        <aside className="w-64 shrink-0 overflow-y-auto border-r p-2">
          {viewUser && currentUser?.id !== viewUser.id && (
            <p
              className="text-muted-foreground px-2 py-2 text-[11px]"
              data-testid="memory-viewing-other"
            >
              正在查看 {viewUser.displayName || viewUser.username}（#{viewUser.id}）的记忆
              —— 编辑/删除都会改到对方的记忆
            </p>
          )}
          {files.length === 0 && (
            <p className="text-muted-foreground px-2 py-4 text-xs" data-testid="memory-empty">
              还没有任何记忆。跟 Agent 聊几轮、让它记住点东西，这里就会出现。
            </p>
          )}
          <ul className="space-y-1">
            {files.map((file) => (
              <li key={file.name}>
                <button
                  type="button"
                  data-testid="memory-file-item"
                  onClick={() => selectFile(file.name)}
                  title={file.name}
                  className={`flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-xs ${
                    file.name === selected ? "bg-accent text-accent-foreground" : "hover:bg-accent/50"
                  }`}
                >
                  <FileText className="text-muted-foreground size-3.5 shrink-0" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium">{fileLabel(file)}</span>
                    <span className="text-muted-foreground block text-[11px]">
                      {formatSize(file.sizeBytes)}
                    </span>
                  </span>
                  {file.kind === "CURATED" && (
                    <Badge variant="outline" className="shrink-0 text-[10px]">
                      精华
                    </Badge>
                  )}
                </button>
              </li>
            ))}
          </ul>
        </aside>

        {/* 右：内容 */}
        <section className="flex min-w-0 flex-1 flex-col">
          {!current ? (
            <div className="text-muted-foreground flex flex-1 items-center justify-center text-sm">
              选左边一份记忆开始查看
            </div>
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-2 border-b px-4 py-2">
                <span className="text-sm font-medium" data-testid="memory-current-name">
                  {current.name}
                </span>
                <span className="text-muted-foreground text-[11px]">
                  {formatSize(current.sizeBytes)}
                  {current.updatedAt && ` · 更新于 ${new Date(current.updatedAt).toLocaleString()}`}
                </span>
                {dirty && (
                  <Badge variant="destructive" className="text-[10px]" data-testid="memory-dirty">
                    未保存
                  </Badge>
                )}
                <div className="ml-auto flex items-center gap-1">
                  <Button
                    variant={mode === "preview" ? "secondary" : "ghost"}
                    size="sm"
                    data-testid="memory-mode-preview"
                    onClick={() => setMode("preview")}
                  >
                    <Eye className="size-3.5" />
                    预览
                  </Button>
                  <Button
                    variant={mode === "edit" ? "secondary" : "ghost"}
                    size="sm"
                    data-testid="memory-mode-edit"
                    onClick={() => setMode("edit")}
                  >
                    <Pencil className="size-3.5" />
                    编辑
                  </Button>
                  <Button
                    size="sm"
                    data-testid="memory-save"
                    disabled={!dirty || saving}
                    onClick={() => void save()}
                    title="保存（Ctrl/Cmd + S）"
                  >
                    <Save className="size-3.5" />
                    保存
                  </Button>
                  {current.kind === "DAILY" && (
                    <Button
                      variant="ghost"
                      size="sm"
                      data-testid="memory-delete"
                      onClick={() => void remove()}
                      title="删除这一天的台账"
                    >
                      <Trash2 className="size-3.5" />
                      删除
                    </Button>
                  )}
                </div>
              </div>

              <div className="min-h-0 flex-1 overflow-auto p-4">
                {loading ? (
                  <p className="text-muted-foreground text-sm">加载中…</p>
                ) : mode === "edit" ? (
                  <textarea
                    data-testid="memory-content"
                    value={draft}
                    spellCheck={false}
                    onChange={(event) => setDraft(event.target.value)}
                    onKeyDown={(event) => {
                      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "s") {
                        event.preventDefault();
                        void save();
                      }
                    }}
                    className="h-full min-h-[60vh] w-full resize-none rounded-md border bg-background p-3 font-mono text-xs leading-relaxed outline-none focus-visible:ring-[3px] focus-visible:ring-ring/30"
                  />
                ) : (
                  <div
                    data-testid="memory-preview"
                    className="prose prose-sm prose-neutral dark:prose-invert max-w-none break-words prose-pre:overflow-x-auto"
                  >
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>
                      {draft || "（空内容）"}
                    </ReactMarkdown>
                  </div>
                )}
              </div>
            </>
          )}
        </section>
      </div>
    </div>
  );
}
