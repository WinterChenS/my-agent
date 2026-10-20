import { FileText, Image as ImageIcon, Package } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { formatSize, isImageArtifact, type ArtifactInfo } from "@/api/artifacts";
import { useArtifactBlobUrl } from "./useArtifactBlobUrl";

/**
 * 产物方框：展示在**对话气泡下方**（Agent 主动交付的产物挂到产出它的那一轮）。
 *
 * <p>可预览的直接给预览（文本显示开头若干行、图片显示缩略图），点预览在**右侧预览面板**里
 * 看完整内容（面板由 ChatPage 统一渲染，这里只回调）。
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

/** 文本类可预览时最多显示几行（点开右侧面板看全文） */
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

