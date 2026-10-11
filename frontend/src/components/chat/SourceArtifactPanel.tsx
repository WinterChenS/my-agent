import { useCallback, useEffect, useState } from "react";
import {
  ChevronDown,
  ChevronRight,
  Download,
  Eye,
  FileText,
  Package,
  Trash2,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  deleteArtifact,
  downloadArtifact,
  formatSize,
  listArtifacts,
  type ArtifactInfo,
} from "@/api/artifacts";

interface Props {
  sessionId: number | null;
  /** 变化即重新拉取（上传、跑完一轮 run 后由父组件 ++）。 */
  refreshKey: number;
  onError: (message: string) => void;
}

/**
 * 右侧栏：分区展示「来源文件」与「产物」（Phase 10 / Task 10.4）。
 *
 * <p>两区都可以折叠；来源文件是用户上传的输入，产物是 Agent 在运行中写出来的。
 * 数据都来自统一的 artifact 表，用 role 区分。
 */
export function SourceArtifactPanel({ sessionId, refreshKey, onError }: Props) {
  const [items, setItems] = useState<ArtifactInfo[]>([]);
  const [openSources, setOpenSources] = useState(true);
  const [openArtifacts, setOpenArtifacts] = useState(true);
  // 文本类产物的在线预览（10.4 门禁：文本类可预览）
  const [preview, setPreview] = useState<ArtifactInfo | null>(null);

  const reload = useCallback(async () => {
    if (sessionId == null) {
      setItems([]);
      return;
    }
    try {
      setItems(await listArtifacts(sessionId));
    } catch (err) {
      onError(err instanceof Error ? err.message : "加载产物失败");
    }
  }, [sessionId, onError]);

  useEffect(() => {
    void reload();
  }, [reload, refreshKey]);

  async function remove(item: ArtifactInfo) {
    try {
      await deleteArtifact(item.id);
      await reload();
    } catch (err) {
      onError(err instanceof Error ? err.message : "删除失败");
    }
  }

  const sources = items.filter((item) => item.role === "SOURCE");
  const artifacts = items.filter((item) => item.role === "ARTIFACT");

  return (
    <>
      <aside className="flex w-72 shrink-0 flex-col border-l">
      <Section
        title="来源文件"
        icon={<FileText className="size-3.5" />}
        count={sources.length}
        open={openSources}
        onToggle={() => setOpenSources((value) => !value)}
        testId="panel-sources"
        emptyHint="还没有来源文件。可以用输入框左侧的按钮上传。"
        items={sources}
        onRemove={remove}
        onError={onError}
        onPreview={setPreview}
      />
      <Section
        title="产物"
        icon={<Package className="size-3.5" />}
        count={artifacts.length}
        open={openArtifacts}
        onToggle={() => setOpenArtifacts((value) => !value)}
        testId="panel-artifacts"
        emptyHint="Agent 运行中写出的文件会出现在这里。"
        items={artifacts}
        onRemove={remove}
        onError={onError}
        onPreview={setPreview}
      />
      </aside>

      <Dialog open={preview !== null} onOpenChange={(open) => !open && setPreview(null)}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle className="truncate">{preview?.fileName}</DialogTitle>
          </DialogHeader>
          <pre
            data-testid="artifact-preview"
            className="max-h-[60vh] overflow-auto rounded-md border bg-muted/40 p-3 text-xs whitespace-pre-wrap"
          >
            {preview?.content ?? "（无预览内容）"}
          </pre>
        </DialogContent>
      </Dialog>
    </>
  );
}

interface SectionProps {
  title: string;
  icon: React.ReactNode;
  count: number;
  open: boolean;
  onToggle: () => void;
  testId: string;
  emptyHint: string;
  items: ArtifactInfo[];
  onRemove: (item: ArtifactInfo) => Promise<void>;
  onError: (message: string) => void;
  onPreview: (item: ArtifactInfo) => void;
}

function Section({
  title,
  icon,
  count,
  open,
  onToggle,
  testId,
  emptyHint,
  items,
  onRemove,
  onError,
  onPreview,
}: SectionProps) {
  return (
    <div className="flex min-h-0 flex-1 flex-col border-b last:border-b-0" data-testid={testId}>
      <button
        type="button"
        className="hover:bg-accent/60 flex items-center gap-1.5 px-3 py-2 text-sm font-medium"
        onClick={onToggle}
      >
        {open ? <ChevronDown className="size-3.5" /> : <ChevronRight className="size-3.5" />}
        {icon}
        <span>{title}</span>
        <Badge variant="secondary" className="ml-auto">
          {count}
        </Badge>
      </button>

      {open && (
        <div className="min-h-0 flex-1 overflow-auto px-2 pb-2">
          {items.length === 0 && (
            <p className="text-muted-foreground px-1 py-2 text-xs">{emptyHint}</p>
          )}
          <ul className="space-y-1">
            {items.map((item) => (
              <li
                key={item.id}
                data-testid={`${testId}-item`}
                className="hover:bg-accent/50 group rounded-md px-2 py-1.5"
              >
                <div className="flex items-center gap-1.5">
                  <span className="truncate text-xs" title={item.fileName}>
                    {item.fileName}
                  </span>
                  <Badge variant="outline" className="ml-auto shrink-0 text-[10px]">
                    {item.storage === "MINIO" ? "对象存储" : "数据库"}
                  </Badge>
                  {item.status === "FAILED" && (
                    <Badge
                      variant="destructive"
                      className="shrink-0 text-[10px]"
                      title={item.errorMessage ?? "存储失败"}
                    >
                      失败
                    </Badge>
                  )}
                </div>
                <div className="mt-0.5 flex items-center gap-1">
                  <span className="text-muted-foreground text-[11px]">
                    {formatSize(item.sizeBytes)}
                  </span>
                  <div className="ml-auto flex opacity-0 transition-opacity group-hover:opacity-100">
                    {item.previewable && (
                      <Button
                        variant="ghost"
                        size="icon"
                        className="size-6"
                        title="预览"
                        data-testid="artifact-preview-button"
                        onClick={() => onPreview(item)}
                      >
                        <Eye className="size-3" />
                      </Button>
                    )}
                    <Button
                      variant="ghost"
                      size="icon"
                      className="size-6"
                      title="下载"
                      onClick={() => {
                        void downloadArtifact(item.id, item.fileName).catch((err: unknown) =>
                          onError(err instanceof Error ? err.message : "下载失败"),
                        );
                      }}
                    >
                      <Download className="size-3" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="size-6"
                      title="删除"
                      onClick={() => void onRemove(item)}
                    >
                      <Trash2 className="size-3" />
                    </Button>
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
