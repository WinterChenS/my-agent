import { Eye, FileText, Image as ImageIcon } from "lucide-react";
import { formatSize, isImageArtifact, type ArtifactInfo } from "@/api/artifacts";

/**
 * 产出物：展示在**对话气泡下方**（挂到产出它的那一轮）。
 *
 * <p>样式（用户要求）：每个产物是一个**小长方形卡片** —— 左边类型图标，中间两行（文件名 / 大小），
 * **右侧一个眼睛图标**（提示“点开能预览”），卡片之间横排、多了自动换行。
 * **不内联正文前几行、不放缩略图**（那些会把产物区撑得很高）。
 * 想看图/正文就点卡片，在**右侧预览面板**里打开（面板由 ChatPage 统一渲染，这里只回调）。
 *
 * <p>数据来自 artifact 表，按 `runId` 与本轮 assistant 消息对应（见 ChatPage 的 runId 关联）。
 */
export function ArtifactBox({
  items,
  onOpen,
}: {
  items: ArtifactInfo[];
  onOpen: (item: ArtifactInfo) => void;
}) {
  return (
    <div data-testid="artifact-box" className="flex flex-wrap gap-2">
      {items.map((item) => (
        <button
          key={item.id}
          type="button"
          data-testid="artifact-open"
          title={`${item.fileName}（点击预览）`}
          onClick={() => onOpen(item)}
          className="hover:bg-accent/50 flex w-56 items-center gap-2 rounded-lg border bg-card px-2.5 py-2 text-left transition-colors"
        >
          {isImageArtifact(item) ? (
            <ImageIcon className="text-muted-foreground size-4 shrink-0" />
          ) : (
            <FileText className="text-muted-foreground size-4 shrink-0" />
          )}
          <span className="min-w-0 flex-1">
            <span className="block truncate text-xs font-medium">{item.fileName}</span>
            <span className="text-muted-foreground block text-[11px]">
              {formatSize(item.sizeBytes)}
            </span>
          </span>
          {/* 眼睛：整张卡片都可点，所以这里只是“能预览”的视觉提示（不嵌套按钮） */}
          <Eye className="text-muted-foreground size-3.5 shrink-0" aria-hidden />
        </button>
      ))}
    </div>
  );
}
