import { Paperclip, X } from "lucide-react";
import { formatAttachmentSize, type StagedAttachment } from "@/lib/attachments/staging";

interface Props {
  items: StagedAttachment[];
  onRemove: (key: string) => void;
  disabled?: boolean;
}

/**
 * 输入框上方的附件暂存区（Phase 11 / D24）。
 *
 * <p>用户选中的文件先在这里以「可移除的条目」显示，**发送时才真正上传**；
 * 因此暂存期间既不在库里产生记录，也不会物化到 Agent 工作区。点条目上的 × 即移除。
 */
export function AttachmentStaging({ items, onRemove, disabled = false }: Props) {
  if (items.length === 0) {
    return null;
  }
  return (
    <div className="mb-2 flex flex-wrap gap-1" data-testid="attachment-staging">
      {items.map((item) => (
        <span
          key={item.key}
          data-testid="attachment-chip"
          className="bg-secondary text-secondary-foreground inline-flex max-w-full items-center gap-1 rounded-md px-2 py-1 text-xs"
          title={item.file.name}
        >
          <Paperclip className="size-3 shrink-0" />
          <span className="max-w-[14rem] truncate">{item.file.name}</span>
          <span className="text-muted-foreground shrink-0">
            {formatAttachmentSize(item.file.size)}
          </span>
          <button
            type="button"
            aria-label={`移除附件 ${item.file.name}`}
            title="移除附件"
            className="hover:text-destructive shrink-0 disabled:opacity-50"
            disabled={disabled}
            onClick={() => onRemove(item.key)}
          >
            <X className="size-3" />
          </button>
        </span>
      ))}
    </div>
  );
}
