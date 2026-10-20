import { Download, FileText, Image as ImageIcon, Package, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { downloadArtifact, formatSize, isImageArtifact, type ArtifactInfo } from "@/api/artifacts";
import { useArtifactBlobUrl } from "./useArtifactBlobUrl";

/**
 * 产物方框：展示在**对话气泡下方**（Agent 主动交付的产物挂到产出它的那一轮）。
 *
 * <p>可预览的直接给预览（文本显示开头若干行、图片显示缩略图），点预览在**右侧抽屉**里看完整内容。
 * 数据来自 artifact 表，按 `runId` 与本轮 assistant 消息对应（见 ChatPage 的 runId 关联）。
 */
export function ArtifactBox({
  items,
  onOpen,
}: {
  items: ArtifactInfo[];
  onOpen: (item: ArtifactInfo) => void;
}) {
  return (
    <div
      data-testid="artifact-box"
      className="bg-muted/20 rounded-xl border p-2.5 text-sm"
    >
      <div className="text-muted-foreground mb-1.5 flex items-center gap-1.5 text-xs font-medium">
        <Package className="size-3.5" />
        产物
        <Badge variant="secondary" className="h-4 px-1.5 text-[10px]">
          {items.length}
        </Badge>
      </div>
      <div className="space-y-2">
        {items.map((item) => (
          <ArtifactPreview key={item.id} item={item} onOpen={() => onOpen(item)} />
        ))}
      </div>
    </div>
  );
}

/** 文本类可预览时最多显示几行（点开抽屉看全文） */
const PREVIEW_LINES = 6;

function ArtifactPreview({ item, onOpen }: { item: ArtifactInfo; onOpen: () => void }) {
  const image = isImageArtifact(item);
  // 图片走带鉴权的下载接口拿 blob（不能直接塞进 <img src>）—— 统一在 hook 里做
  const { url: blobUrl, failed } = useArtifactBlobUrl(item);

  const previewable = item.previewable && typeof item.content === "string";
  const previewText = previewable
    ? (item.content ?? "").split("\n").slice(0, PREVIEW_LINES).join("\n")
    : "";

  return (
    <div className="bg-background/70 rounded-lg border">
      <button
        type="button"
        data-testid="artifact-open"
        onClick={onOpen}
        className="hover:bg-accent/40 flex w-full items-center gap-2 rounded-t-lg px-2.5 py-1.5 text-left"
        title="点击预览"
      >
        {image ? (
          <ImageIcon className="text-muted-foreground size-3.5 shrink-0" />
        ) : (
          <FileText className="text-muted-foreground size-3.5 shrink-0" />
        )}
        <span className="min-w-0 flex-1 truncate text-xs font-medium">{item.fileName}</span>
        <span className="text-muted-foreground shrink-0 text-[11px]">
          {formatSize(item.sizeBytes)}
        </span>
      </button>

      {/* 可预览的给个预览：文本显示开头几行；图片显示缩略图 */}
      {previewable && (
        <button
          type="button"
          data-testid="artifact-preview-inline"
          onClick={onOpen}
          className="hover:bg-accent/20 block w-full rounded-b-lg border-t px-2.5 py-1.5 text-left"
          title="点击在右侧打开预览"
        >
          <pre className="text-muted-foreground max-h-24 overflow-hidden text-[11px] leading-relaxed whitespace-pre-wrap">
            {previewText || "（空内容）"}
          </pre>
        </button>
      )}
      {image && !failed && (
        <button
          type="button"
          data-testid="artifact-preview-inline"
          onClick={onOpen}
          className="hover:bg-accent/20 block w-full rounded-b-lg border-t p-1.5 text-left"
          title="点击在右侧打开预览"
        >
          {blobUrl ? (
            <img src={blobUrl} alt={item.fileName} className="max-h-32 rounded object-contain" />
          ) : (
            <span className="text-muted-foreground text-[11px]">加载预览…</span>
          )}
        </button>
      )}
    </div>
  );
}

/**
 * 右侧预览抽屉：点击气泡下方的预览时从右边滑出。
 *
 * <p>文本直接显示正文；图片显示大图；其它类型提示下载。
 */
export function ArtifactPreviewDrawer({
  item,
  onClose,
}: {
  item: ArtifactInfo | null;
  onClose: () => void;
}) {
  const { url: blobUrl, failed } = useArtifactBlobUrl(item);

  if (!item) {
    return null;
  }
  const image = isImageArtifact(item);

  return (
    <div className="fixed inset-0 z-50 flex justify-end" data-testid="artifact-drawer">
      {/* 遮罩：点击关闭 */}
      <div className="absolute inset-0 bg-black/30" onClick={onClose} />
      <aside className="bg-background relative flex h-full w-[min(760px,92vw)] flex-col border-l shadow-xl">
        <div className="flex items-center gap-2 border-b p-3">
          <FileText className="text-muted-foreground size-4 shrink-0" />
          <span className="min-w-0 flex-1 truncate text-sm font-medium" title={item.fileName}>
            {item.fileName}
          </span>
          <span className="text-muted-foreground shrink-0 text-xs">
            {formatSize(item.sizeBytes)}
          </span>
          <Button
            variant="ghost"
            size="icon"
            className="size-7"
            title="下载"
            data-testid="artifact-download"
            onClick={() => void downloadArtifact(item.id, item.fileName)}
          >
            <Download className="size-4" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="size-7"
            title="关闭"
            data-testid="artifact-drawer-close"
            onClick={onClose}
          >
            <X className="size-4" />
          </Button>
        </div>

        <div className="min-h-0 flex-1 overflow-auto p-3">
          {image ? (
            failed ? (
              <p className="text-muted-foreground text-sm">图片加载失败，可点右上角下载。</p>
            ) : blobUrl ? (
              <img src={blobUrl} alt={item.fileName} className="mx-auto max-w-full rounded" />
            ) : (
              <p className="text-muted-foreground text-sm">加载中…</p>
            )
          ) : item.previewable && typeof item.content === "string" ? (
            <pre
              data-testid="artifact-preview-full"
              className="bg-muted/40 rounded-md border p-3 text-xs whitespace-pre-wrap"
            >
              {item.content || "（空内容）"}
            </pre>
          ) : (
            <p className="text-muted-foreground text-sm">
              这种类型不支持在线预览，可点右上角下载后查看。
            </p>
          )}
        </div>
      </aside>
    </div>
  );
}
