import { useEffect, useRef, useState } from "react";
import { Brain, ChevronDown, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ChatItem } from "@/lib/agui/types";

type ThinkingItem = Extract<ChatItem, { kind: "thinking" }>;

/**
 * 思考过程块。
 *
 * <p>交互约定（用户明确要求）：
 * <ul>
 *   <li><b>思考中展开</b>：内容一边流式出现一边看得见；</li>
 *   <li><b>思考完成自动折叠</b>：只留一行「已思考（N 字）」，点一下还能展开回看；</li>
 *   <li><b>不落库</b>：只存在于本轮流式过程，刷新后消失（后端也不写 chat_message）。</li>
 * </ul>
 *
 * <p>为什么用本地 `open` 而不是把折叠态放进 reducer：折叠是**纯展示状态**，
 * 放进全局状态会让每次 delta 都触发一次全树重渲染，长思考（几千 token）时明显卡。
 */
export function ThinkingBlock({ item }: { item: ThinkingItem }) {
  // 思考中默认展开（看得见增量）；**已结束的默认折叠** ——
  // 历史会话回放时 thinking 一上来就是 streaming=false，若还用 true，
  // 刷新后一堆思考块全摊开，会把对话正文顶下去。
  const [open, setOpen] = useState(item.streaming);
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const wasStreaming = useRef(item.streaming);

  // 流式结束 → 自动折叠（"想完了就收起来"）
  useEffect(() => {
    if (wasStreaming.current && !item.streaming) {
      setOpen(false);
    }
    wasStreaming.current = item.streaming;
  }, [item.streaming]);

  // 思考中内容增长时保持贴底（否则用户要手动往下拖）
  useEffect(() => {
    if (item.streaming && open && bodyRef.current) {
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
    }
  }, [item.content, item.streaming, open]);

  const chars = item.content.length;

  return (
    <div
      className="border-border/60 rounded-md border border-dashed bg-muted/30"
      data-testid="thinking-block"
    >
      <button
        type="button"
        className="text-muted-foreground flex w-full items-center gap-1.5 px-2.5 py-1.5 text-xs hover:text-foreground"
        onClick={() => setOpen((value) => !value)}
        data-testid="thinking-toggle"
      >
        {open ? <ChevronDown className="size-3" /> : <ChevronRight className="size-3" />}
        <Brain className={cn("size-3.5", item.streaming && "animate-pulse")} />
        {item.streaming ? (
          <span>正在思考…</span>
        ) : (
          <span>已思考（{chars} 字）</span>
        )}
        {open && item.streaming && (
          <span className="ml-auto tabular-nums">{chars}</span>
        )}
      </button>
      {/* 丝滑折叠：Grid 0fr → 1fr，不用 JS 算高度 */}
      <div
        data-testid="thinking-collapse"
        data-open={open ? "true" : "false"}
        className={cn(
          "grid transition-[grid-template-rows] duration-300 ease-out",
          open ? "grid-rows-[1fr]" : "grid-rows-[0fr]",
        )}
      >
        <div className="min-h-0 overflow-hidden">
          <div
            ref={bodyRef}
            className="text-muted-foreground max-h-[260px] overflow-auto border-t border-dashed px-2.5 py-2 text-xs leading-relaxed whitespace-pre-wrap"
            data-testid="thinking-content"
          >
            {item.content || "…"}
          </div>
        </div>
      </div>
    </div>
  );
}
