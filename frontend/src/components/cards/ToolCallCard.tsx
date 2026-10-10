import { useState } from "react";
import { ChevronDown, ChevronRight, Wrench } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ChatItem } from "@/lib/agui/types";

type ToolItem = Extract<ChatItem, { kind: "tool" }>;

const STATE_LABEL: Record<ToolItem["state"], string> = {
  args: "参数接收中",
  ready: "待执行",
  done: "已完成",
};

/** 参数压成一行做预览，避免占高度。 */
function preview(args: string, max = 110): string {
  return args.replace(/\s+/g, " ").trim().slice(0, max);
}

/**
 * 工具调用行（紧凑）。
 *
 * <p>刻意**不用 Card**：shadcn 的 Card 自带 `py-6 gap-6`，一个单行工具调用会占掉
 * 百来像素，几个工具就能吃掉整屏（用户实测反馈“太大”）。
 * 这里用单行 + 可展开，收起时约 30px。
 */
export function ToolCallCard({ item }: { item: ToolItem }) {
  const [open, setOpen] = useState(false);
  const summary = preview(item.args);

  return (
    <div className="border-border/70 bg-muted/30 rounded-md border border-dashed">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="hover:bg-muted/60 flex w-full items-center gap-2 rounded-md px-2 py-1 text-left text-xs"
      >
        {open ? (
          <ChevronDown className="text-muted-foreground size-3.5 shrink-0" />
        ) : (
          <ChevronRight className="text-muted-foreground size-3.5 shrink-0" />
        )}
        <Wrench className="text-muted-foreground size-3.5 shrink-0" />
        <span className="shrink-0 font-mono">{item.name}</span>
        {!open && summary && (
          <span className="text-muted-foreground min-w-0 flex-1 truncate">{summary}</span>
        )}
        <span
          className={cn(
            "ml-auto shrink-0 rounded px-1.5 py-0.5 text-[10px]",
            item.state === "done"
              ? "bg-muted text-muted-foreground"
              : "bg-amber-100 text-amber-700",
          )}
        >
          {STATE_LABEL[item.state]}
        </span>
      </button>

      {open && (
        <div className="space-y-1.5 px-2 pb-2 pt-1 text-xs">
          <div>
            <div className="text-muted-foreground mb-0.5">参数</div>
            <pre className="bg-background max-h-48 overflow-auto rounded p-2 font-mono text-[11px]">
              {item.args || "（无）"}
            </pre>
          </div>
          {item.result !== null && (
            <div>
              <div className="text-muted-foreground mb-0.5">结果</div>
              <pre className="bg-background max-h-64 overflow-auto rounded p-2 font-mono text-[11px]">
                {item.result}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * 连续工具调用的分组：默认折叠成一行，避免一次任务里十几个工具把聊天记录淹没。
 */
export function ToolCallGroup({ items }: { items: ToolItem[] }) {
  const [open, setOpen] = useState(false);
  const running = items.some((i) => i.state !== "done");
  const lastName = items[items.length - 1]?.name ?? "";

  return (
    <div className="space-y-1">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="text-muted-foreground hover:text-foreground flex items-center gap-1.5 text-xs"
      >
        {open ? <ChevronDown className="size-3.5" /> : <ChevronRight className="size-3.5" />}
        <Wrench className="size-3.5" />
        <span>
          执行了 {items.length} 个工具
          {!open && lastName && <span className="font-mono"> · 最近 {lastName}</span>}
        </span>
        {running && <span className="text-amber-600">进行中…</span>}
      </button>
      {open && (
        <div className="space-y-1 border-l pl-2">
          {items.map((item) => (
            <ToolCallCard key={item.id} item={item} />
          ))}
        </div>
      )}
    </div>
  );
}
