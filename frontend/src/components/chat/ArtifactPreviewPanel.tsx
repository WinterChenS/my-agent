import { Download, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { downloadArtifact, formatSize, type ArtifactInfo } from "@/api/artifacts";
import { ArtifactPreviewBody } from "./ArtifactPreviewBody";

/**
 * 右侧预览面板：**内联挤压**聊天区（不是遮罩抽屉），可整体关闭；多产物用上方 tab 分开。
 *
 * <p>内容交给 {@link ArtifactPreviewBody} 渲染 —— 同一种文件在哪儿看都一样。
 */
export function ArtifactPreviewPanel({
  tabs,
  activeId,
  onSelect,
  onCloseTab,
  onClose,
}: {
  tabs: ArtifactInfo[];
  activeId: number | null;
  onSelect: (id: number) => void;
  onCloseTab: (id: number) => void;
  onClose: () => void;
}) {
  const active = tabs.find((item) => item.id === activeId) ?? tabs[0] ?? null;
  if (!active) {
    return null;
  }

  return (
    <aside
      data-testid="preview-panel"
      className="flex w-[28rem] shrink-0 flex-col border-l"
    >
      {/* tab 行：一个产物一个 tab，可逐个关闭 */}
      <div className="flex items-center gap-1 border-b px-2 py-1.5">
        <div className="flex min-w-0 flex-1 items-center gap-1 overflow-x-auto">
          {tabs.map((item) => (
            <span
              key={item.id}
              data-testid="preview-tab"
              className={`flex max-w-[12rem] shrink-0 items-center gap-1 rounded-md px-2 py-1 text-xs ${
                item.id === active.id ? "bg-accent text-accent-foreground" : "text-muted-foreground"
              }`}
            >
              <button
                type="button"
                className="min-w-0 truncate"
                title={item.fileName}
                onClick={() => onSelect(item.id)}
              >
                {item.fileName}
              </button>
              <button
                type="button"
                title="关闭这个预览"
                data-testid="preview-tab-close"
                className="hover:bg-background/60 rounded p-0.5"
                onClick={() => onCloseTab(item.id)}
              >
                <X className="size-3" />
              </button>
            </span>
          ))}
        </div>
        <Button
          variant="ghost"
          size="icon"
          className="size-7 shrink-0"
          title="关闭预览"
          data-testid="preview-panel-close"
          onClick={onClose}
        >
          <X className="size-4" />
        </Button>
      </div>

      {/* 内容区 */}
      <div className="min-h-0 flex-1 overflow-auto p-3">
        <div className="mb-2 flex items-center gap-2">
          <span className="min-w-0 flex-1 truncate text-xs text-muted-foreground" title={active.fileName}>
            {active.fileName} · {formatSize(active.sizeBytes)}
          </span>
          <Button
            variant="ghost"
            size="icon"
            className="size-7 shrink-0"
            title="下载"
            data-testid="preview-download"
            onClick={() => void downloadArtifact(active.id, active.fileName)}
          >
            <Download className="size-4" />
          </Button>
        </div>
        <ArtifactPreviewBody item={active} />
      </div>
    </aside>
  );
}
