import { useState } from "react";
import { Bot, ChevronDown, ChevronRight, Wrench } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { SubagentItem } from "@/lib/agui/types";

/**
 * 子 Agent 输出卡片（Phase 12 / D26）。
 *
 * <p>形态与「工具调用组」一致：**收起只占一行**（子 Agent 名 + 状态 + 工具调用次数），
 * 展开才显示它的正文与工具行 —— 官方 `subagent.*` 事件是逐 delta 发的，
 * 以前每个 delta 都会变成一张「未识别事件」JSON 卡，把聊天记录淹掉。
 */
export function SubagentCard({ item }: { item: SubagentItem }) {
  const [expanded, setExpanded] = useState(false);
  const running = item.status === "running";
  const summary = [
    running ? "生成中…" : "已结束",
    item.toolCalls.length > 0 ? `${item.toolCalls.length} 次工具调用` : null,
  ]
    .filter(Boolean)
    .join(" · ");

  return (
    <div
      data-testid="subagent-card"
      className="border-muted-foreground/25 bg-muted/20 w-full rounded-lg border"
    >
      <button
        type="button"
        className="hover:bg-accent/40 flex w-full items-center gap-1.5 rounded-lg px-2 py-1.5 text-left text-xs"
        onClick={() => setExpanded((value) => !value)}
      >
        {expanded ? (
          <ChevronDown className="text-muted-foreground size-3.5 shrink-0" />
        ) : (
          <ChevronRight className="text-muted-foreground size-3.5 shrink-0" />
        )}
        <Bot className="text-muted-foreground size-3.5 shrink-0" />
        <span className="text-muted-foreground truncate">
          子 Agent · <span className="text-foreground font-medium">{item.name}</span>
        </span>
        <span className="text-muted-foreground ml-auto shrink-0">{summary}</span>
        {running && (
          <span className="bg-foreground/60 ml-1 inline-block size-1.5 shrink-0 animate-pulse rounded-full" />
        )}
      </button>

      {expanded && (
        <div className="space-y-1.5 px-2 pt-0.5 pb-2">
          {item.confirm && (
            <p className="text-muted-foreground text-[11px]">⚠ {item.confirm}</p>
          )}
          {item.thinking && (
            <p className="text-muted-foreground/80 text-[11px] whitespace-pre-wrap">
              {item.thinking}
            </p>
          )}
          {item.text && (
            <pre
              data-testid="subagent-text"
              className="text-foreground/90 max-h-64 overflow-auto rounded border bg-background/60 p-2 text-[11px] whitespace-pre-wrap"
            >
              {item.text}
            </pre>
          )}
          {item.toolCalls.length > 0 && (
            <ul className="space-y-0.5">
              {item.toolCalls.map((call, index) => (
                <li
                  key={`${call.name}-${index}`}
                  className="text-muted-foreground flex items-center gap-1 text-[11px]"
                >
                  <Wrench className="size-3 shrink-0" />
                  <span className="truncate">{call.name}</span>
                  <span className={cn("ml-1", call.done ? "text-emerald-600" : "")}>
                    {call.done ? "✓" : "…"}
                  </span>
                </li>
              ))}
            </ul>
          )}
          {!item.text && !item.thinking && item.toolCalls.length === 0 && !item.confirm && (
            <p className="text-muted-foreground text-[11px]">（暂无输出）</p>
          )}
          <Badge variant="outline" className="text-[10px]">
            {item.source}
          </Badge>
        </div>
      )}
    </div>
  );
}
