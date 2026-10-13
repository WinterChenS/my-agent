import { useMemo, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Eye, Pencil, Terminal } from "lucide-react";

/** 可插入的工具项（MCP 工具或本地工具）。 */
export interface ToolOption {
  /** 插入到正文里的名字（模型实际要调用的名字）。 */
  name: string;
  /** 展示用的补充说明。 */
  hint?: string | null;
  /** 来源分组：MCP 服务器名 / 本地。 */
  group: string;
}

/**
 * Skill 正文的 Markdown 编辑器（左编辑 / 右预览，实时渲染）。
 *
 * 为什么要它：Skill 正文是写给模型看的 Markdown，原来只有一个光秃秃的 textarea，
 * 作者没法确认「渲染出来长什么样」，也容易把列表、表格写错。
 *
 * 「/」唤起工具：写 Skill 时常要指名道姓地引用某个工具（「用 xxx 工具查订单」）。
 * 手打名字容易拼错（尤其 MCP 工具还带 `server__tool` 命名空间），所以支持输入 `/` 弹出
 * 可用工具列表、选中即插入。
 */
export function SkillMarkdownEditor({
  value,
  onChange,
  tools,
  disabled,
}: {
  value: string;
  onChange: (next: string) => void;
  /** 可插入的工具（MCP + 本地）；为空时「/」只做普通字符。 */
  tools: ToolOption[];
  disabled?: boolean;
}) {
  const areaRef = useRef<HTMLTextAreaElement>(null);
  const [slashQuery, setSlashQuery] = useState<string | null>(null);
  const [highlight, setHighlight] = useState(0);
  const [showPreview, setShowPreview] = useState(true);

  const filtered = useMemo(() => {
    if (slashQuery === null) {
      return [];
    }
    const q = slashQuery.toLowerCase();
    return tools
      .filter((t) => !q || t.name.toLowerCase().includes(q) || (t.hint ?? "").toLowerCase().includes(q))
      .slice(0, 12);
  }, [slashQuery, tools]);

  /** 在光标处插入文本，并把光标移到插入内容之后。 */
  function insertAtCursor(text: string, replaceSlash: boolean) {
    const area = areaRef.current;
    if (!area) {
      return;
    }
    const start = area.selectionStart ?? value.length;
    const end = area.selectionEnd ?? value.length;
    const before = value.slice(0, start);
    const after = value.slice(end);
    // 把触发菜单的那个「/」连同后面已输入的查询词一起去掉
    const slashIndex = before.lastIndexOf("/");
    const base = replaceSlash && slashIndex >= 0 ? before.slice(0, slashIndex) : before;
    const next = `${base}${text}${after}`;
    onChange(next);
    requestAnimationFrame(() => {
      const caret = base.length + text.length;
      area.focus();
      area.setSelectionRange(caret, caret);
    });
  }

  function handleChange(next: string) {
    onChange(next);
    const area = areaRef.current;
    if (!area) {
      return;
    }
    const before = next.slice(0, area.selectionStart ?? next.length);
    const idx = before.lastIndexOf("/");
    if (idx >= 0 && !/\s/.test(before.slice(idx + 1))) {
      // 「/」后面还没打空格 → 认为在唤起工具菜单
      setSlashQuery(before.slice(idx + 1));
      setHighlight(0);
    } else {
      setSlashQuery(null);
    }
  }

  function pick(index: number) {
    const option = filtered[index];
    if (!option) {
      return;
    }
    insertAtCursor(`\`${option.name}\``, true);
    setSlashQuery(null);
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-1 text-sm">
      <div className="flex items-center justify-between">
        <span>
          内容（Markdown）
          <span className="text-muted-foreground ml-2 text-xs">
            输入 <kbd className="rounded border px-1">/</kbd> 可插入 MCP / 本地工具名
          </span>
        </span>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          data-testid="skill-preview-toggle"
          onClick={() => setShowPreview((v) => !v)}
        >
          {showPreview ? <Pencil className="size-3.5" /> : <Eye className="size-3.5" />}
          {showPreview ? "只看编辑" : "显示预览"}
        </Button>
      </div>

      <div className={cn("grid min-h-0 flex-1 gap-3", showPreview ? "lg:grid-cols-2" : "grid-cols-1")}>
        {/* 编辑区 */}
        <div className="relative flex min-h-0 flex-col">
          <textarea
            ref={areaRef}
            data-testid="skill-content-input"
            className="border-input min-h-[240px] w-full flex-1 resize-none rounded-md border bg-transparent p-3 font-mono text-xs leading-relaxed"
            value={value}
            disabled={disabled}
            placeholder={"# 规则\n\n回答时在末尾加【OK】。\n\n需要查订单时调用 `orders__query`。"}
            onChange={(e) => handleChange(e.target.value)}
            onKeyDown={(e) => {
              if (slashQuery === null || filtered.length === 0) {
                return;
              }
              if (e.key === "ArrowDown") {
                e.preventDefault();
                setHighlight((h) => (h + 1) % filtered.length);
              } else if (e.key === "ArrowUp") {
                e.preventDefault();
                setHighlight((h) => (h - 1 + filtered.length) % filtered.length);
              } else if (e.key === "Enter" || e.key === "Tab") {
                e.preventDefault();
                pick(highlight);
              } else if (e.key === "Escape") {
                setSlashQuery(null);
              }
            }}
            onBlur={() => window.setTimeout(() => setSlashQuery(null), 150)}
          />

          {/* 工具候选菜单 */}
          {slashQuery !== null && filtered.length > 0 && (
            <div
              data-testid="skill-tool-menu"
              className="bg-popover absolute right-0 bottom-0 left-0 z-50 max-h-56 overflow-auto rounded-md border shadow-lg"
            >
              {filtered.map((option, index) => (
                <button
                  key={`${option.group}:${option.name}`}
                  type="button"
                  data-testid="skill-tool-option"
                  className={cn(
                    "flex w-full items-center justify-between gap-2 px-3 py-1.5 text-left text-xs",
                    index === highlight ? "bg-accent" : "hover:bg-accent/60",
                  )}
                  onMouseEnter={() => setHighlight(index)}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    pick(index);
                  }}
                >
                  <span className="flex items-center gap-1.5">
                    <Terminal className="size-3 shrink-0 opacity-60" />
                    <code className="font-mono">{option.name}</code>
                  </span>
                  <span className="text-muted-foreground truncate">
                    {option.group}
                    {option.hint ? ` · ${option.hint}` : ""}
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>

        {/* 预览区：与聊天里助手消息用同一套 prose 样式（含代码块对比度修复） */}
        {showPreview && (
          <div
            data-testid="skill-content-preview"
            className={cn(
              "border-input min-h-[240px] flex-1 overflow-auto rounded-md border p-3",
              "prose prose-sm prose-neutral dark:prose-invert max-w-none",
              "prose-p:my-1.5 prose-headings:mt-3 prose-headings:mb-1.5 prose-headings:font-semibold",
              "prose-ul:my-1.5 prose-ol:my-1.5 prose-li:my-0.5",
              "prose-pre:my-2 prose-pre:rounded-lg prose-pre:border prose-pre:bg-muted prose-pre:p-3 prose-pre:text-xs prose-pre:text-foreground",
              "prose-code:rounded prose-code:bg-muted prose-code:px-1 prose-code:py-0.5 prose-code:text-[0.85em] prose-code:text-foreground prose-code:before:content-none prose-code:after:content-none",
              "[&_pre_code]:bg-transparent [&_pre_code]:p-0 [&_pre_code]:text-xs [&_pre_code]:text-inherit",
              "prose-table:my-2 prose-th:px-2 prose-td:px-2",
            )}
          >
            {value.trim() ? (
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{value}</ReactMarkdown>
            ) : (
              <p className="text-muted-foreground text-xs">左侧输入 Markdown，这里实时预览。</p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
