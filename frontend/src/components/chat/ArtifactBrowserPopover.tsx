import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Download, Eye, FileText, Package, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import {
  deleteArtifact,
  downloadArtifact,
  formatSize,
  listArtifacts,
  type ArtifactInfo,
} from "@/api/artifacts";
import { canPreviewArtifact } from "@/lib/artifacts/previewKind";

interface Props {
  /** 触发按钮本身（Popover 直接锚在它下方）。 */
  children: ReactNode;
  sessionId: number | null;
  /** 变化即重新拉取（上传、跑完一轮 run 后由父组件 ++）。 */
  refreshKey: number;
  onError: (message: string) => void;
  /** 点「预览」→ 在右侧预览面板里打开，并关掉本弹窗。 */
  onPreview: (item: ArtifactInfo) => void;
}

/**
 * 「来源文件与产物」清单弹窗。
 *
 * <p>以前它是聊天区右边**常驻**的一列（`w-72`），把聊天区压得很窄。现在改成**按钮 + 弹窗**：
 * 弹窗紧贴按钮**下方**弹出，**竖长条**（`w-80`、高 24rem，比宽大）——列表本来就是竖着长的，
 * 这样比居中的大对话框更贴近“从按钮里拉出一张清单”的感觉。
 *
 * <p>分栏按「有没有内容」显示：只有来源文件就只出「来源文件」一栏，空的分栏不占位置。
 *
 * <p>数据来自统一的 artifact 表，用 `role` 区分来源 / 产物。
 */
export function ArtifactBrowserPopover({
  children,
  sessionId,
  refreshKey,
  onError,
  onPreview,
}: Props) {
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<ArtifactInfo[]>([]);

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

  // 关着的时候不拉：每次打开都取一份最新的（上传/跑完一轮后会变）
  useEffect(() => {
    if (!open) {
      return;
    }
    void reload();
  }, [reload, refreshKey, open]);

  async function remove(item: ArtifactInfo) {
    try {
      await deleteArtifact(item.id);
      await reload();
    } catch (err) {
      onError(err instanceof Error ? err.message : "删除失败");
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>{children}</PopoverTrigger>
      <PopoverContent
        data-testid="artifact-browser"
        // 锚在按钮**下方**，并从按钮左边缘开始往右展开
        side="bottom"
        align="start"
        sideOffset={6}
        className="flex h-[24rem] max-h-[70vh] w-80 flex-col p-0"
      >
        <div className="border-b px-3 py-2">
          <p className="text-sm font-medium">来源文件与产物</p>
          <p className="text-muted-foreground mt-0.5 text-[11px]">
            点「预览」在右侧面板打开（滚轮可看更多）
          </p>
        </div>

        <div className="min-h-0 flex-1 overflow-auto p-2">
          <ArtifactBrowserList
            items={items}
            onRemove={remove}
            onError={onError}
            onPreview={(item) => {
              onPreview(item);
              // 选完就关：右侧面板已经是完整视图，弹窗再开着挡着没意义
              setOpen(false);
            }}
          />
        </div>
      </PopoverContent>
    </Popover>
  );
}

/**
 * 清单内容（**与弹窗壳解耦**，单独可测）。
 *
 * <p>只有存在对应类型时才渲染该分栏（用户要求）；两类都没有时给一句空提示。
 */
export function ArtifactBrowserList({
  items,
  onRemove,
  onError,
  onPreview,
}: {
  items: ArtifactInfo[];
  onRemove: (item: ArtifactInfo) => Promise<void>;
  onError: (message: string) => void;
  onPreview: (item: ArtifactInfo) => void;
}) {
  const sources = items.filter((item) => item.role === "SOURCE");
  const artifacts = items.filter((item) => item.role === "ARTIFACT");

  if (sources.length === 0 && artifacts.length === 0) {
    return (
      <p
        className="text-muted-foreground py-6 text-center text-sm"
        data-testid="artifact-browser-empty"
      >
        这个会话还没有来源文件或产物。
      </p>
    );
  }

  return (
    <div className="space-y-3">
      {sources.length > 0 && (
        <Section
          title="来源文件"
          icon={<FileText className="size-3.5" />}
          testId="panel-sources"
          items={sources}
          onRemove={onRemove}
          onError={onError}
          onPreview={onPreview}
        />
      )}
      {artifacts.length > 0 && (
        <Section
          title="产物"
          icon={<Package className="size-3.5" />}
          testId="panel-artifacts"
          items={artifacts}
          onRemove={onRemove}
          onError={onError}
          onPreview={onPreview}
        />
      )}
    </div>
  );
}

interface SectionProps {
  title: string;
  icon: ReactNode;
  testId: string;
  items: ArtifactInfo[];
  onRemove: (item: ArtifactInfo) => Promise<void>;
  onError: (message: string) => void;
  onPreview: (item: ArtifactInfo) => void;
}

/** 一栏（来源文件 / 产物）：只在**有内容**时被渲染（由调用方判断）。 */
function Section({ title, icon, testId, items, onRemove, onError, onPreview }: SectionProps) {
  return (
    <div data-testid={testId}>
      <div className="mb-1 flex items-center gap-1.5 text-sm font-medium">
        {icon}
        <span>{title}</span>
        <Badge variant="secondary" className="ml-auto">
          {items.length}
        </Badge>
      </div>
      <ul className="space-y-1">
        {items.map((item) => (
          <li
            key={item.id}
            data-testid={`${testId}-item`}
            className="hover:bg-accent/50 rounded-md border px-2 py-1.5"
          >
            <div className="flex items-center gap-1.5">
              <span className="min-w-0 truncate text-xs" title={item.fileName}>
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
              <div className="ml-auto flex items-center gap-1">
                {canPreviewArtifact(item) && (
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
  );
}
