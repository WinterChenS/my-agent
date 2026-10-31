import { useState } from "react";
import { Bot, ChevronDown, ChevronRight, Wrench } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { SubagentItem, SubagentStep } from "@/lib/agui/types";

/**
 * 子 Agent 输出卡片（Phase 12 / D26）。
 *
 * <p>形态与「工具调用组」一致：**收起只占一行**（子 Agent 名 + 状态 + 工具调用次数），
 * 展开才显示它的步骤。
 *
 * <p>展开内容按 {@link SubagentItem.steps} 的**到达顺序**渲染 —— 子 Agent 的思考、
 * 正文、工具调用是交错发生的，必须保持时间顺序才读得懂（以前按类型分组渲染是错的）。
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

  // 老数据（没有 steps）退化用旧字段，避免历史会话空白
  const steps: SubagentStep[] =
    item.steps && item.steps.length > 0
      ? item.steps
      : [
          ...(item.thinking ? [{ kind: "thinking", text: item.thinking } as SubagentStep] : []),
          ...(item.text ? [{ kind: "text", text: item.text } as SubagentStep] : []),
          ...item.toolCalls.map((call) => ({ kind: "tool", ...call }) as SubagentStep),
        ];

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
        <div className="space-y-1.5 px-2 pt-0.5 pb-2" data-testid="subagent-steps">
          {item.confirm && (
            <p className="text-muted-foreground text-[11px]">⚠ {item.confirm}</p>
          )}
          {steps.map((step, index) => (
            <SubagentStepRow key={`${step.kind}-${index}`} step={step} />
          ))}
          {steps.length === 0 && !item.confirm && (
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

function SubagentStepRow({ step }: { step: SubagentStep }) {
  if (step.kind === "tool") {
    return (
      <div className="text-muted-foreground flex items-center gap-1 text-[11px]" data-testid="subagent-step-tool">
        <Wrench className="size-3 shrink-0" />
        <span className="truncate">{step.name}</span>
        <span className={cn("ml-1", step.done ? "text-emerald-600" : "")}>{step.done ? "✓" : "…"}</span>
      </div>
    );
  }
  if (step.kind === "thinking") {
    return (
      <p
        className="text-muted-foreground/80 text-[11px] whitespace-pre-wrap"
        data-testid="subagent-step-thinking"
      >
        {step.text}
      </p>
    );
  }
  return (
    <pre
      data-testid="subagent-step-text"
      className="text-foreground/90 max-h-64 overflow-auto rounded border bg-background/60 p-2 text-[11px] whitespace-pre-wrap"
    >
      {step.text}
    </pre>
  );
}
