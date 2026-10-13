import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  BlockTypeSelect,
  BoldItalicUnderlineToggles,
  CreateLink,
  DiffSourceToggleWrapper,
  InsertCodeBlock,
  InsertTable,
  InsertThematicBreak,
  ListsToggle,
  MDXEditor,
  UndoRedo,
  codeBlockPlugin,
  codeMirrorPlugin,
  diffSourcePlugin,
  headingsPlugin,
  linkDialogPlugin,
  linkPlugin,
  listsPlugin,
  markdownShortcutPlugin,
  quotePlugin,
  tablePlugin,
  thematicBreakPlugin,
  toolbarPlugin,
  type MDXEditorMethods,
} from "@mdxeditor/editor";
import "@mdxeditor/editor/style.css";
import { Terminal } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

/** 可插入的工具项。 */
export interface ToolOption {
  /** 插入到正文里的名字（模型实际要调用的名字）。 */
  name: string;
  hint?: string | null;
  /** 来源分组：MCP 服务器名 / 本地。 */
  group: string;
}

const CODE_LANGUAGES: Record<string, string> = {
  "": "纯文本",
  json: "JSON",
  yaml: "YAML",
  bash: "Shell",
  sql: "SQL",
  java: "Java",
  javascript: "JavaScript",
  typescript: "TypeScript",
  python: "Python",
  markdown: "Markdown",
};

/**
 * Skill 正文编辑器：**单框所见即所得**（类似 Notion），底层 @mdxeditor/editor（Lexical）。
 *
 * 为什么不自搓：见项目讨论——透明 textarea 叠渲染层会在标题/表格处错位；
 * 自己 contentEditable + 每次重渲染会弹光标、打断中文输入法。
 * 编辑器内部维护富文本模型，但**对外读写仍是纯 Markdown**（markdown 进 / onChange 出），
 * 技能内容格式与喂给模型的内容都不变。
 *
 * 「/」唤起工具的实现方式（踩过坑后定的）：
 * 在 **keydown 捕获阶段**把 `/` 拦下来、不让它进入文档，之后的字符只用于过滤菜单；
 * 选中时用 `insertMarkdown` 在光标处插入。
 * —— 早先的做法是先把 `/query` 打进文档、选中时再删掉，但 Lexical 有自己的编辑器模型，
 *    直接删 DOM 会被它还原，结果正文里留下 `/read`（实测）。
 */
export function SkillMdxEditor({
  value,
  onChange,
  tools,
  placeholder,
}: {
  value: string;
  onChange: (next: string) => void;
  tools: ToolOption[];
  placeholder?: string;
}) {
  const editorRef = useRef<MDXEditorMethods>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  /** null = 菜单关闭；字符串 = 当前过滤词。 */
  const [query, setQuery] = useState<string | null>(null);
  const [highlight, setHighlight] = useState(0);

  const filtered = useMemo(() => {
    const q = (query ?? "").toLowerCase();
    return tools
      .filter((t) => !q || t.name.toLowerCase().includes(q) || (t.hint ?? "").toLowerCase().includes(q))
      .slice(0, 12);
  }, [query, tools]);

  const insertTool = useCallback((name: string) => {
    editorRef.current?.insertMarkdown("`" + name + "`");
    editorRef.current?.focus();
    setQuery(null);
  }, []);

  // 捕获阶段处理按键：开菜单 / 过滤 / 上下选 / 回车插入 / ESC 关闭
  useEffect(() => {
    const el = wrapRef.current;
    if (!el) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      const plain = !event.ctrlKey && !event.metaKey && !event.altKey;
      if (query === null) {
        // 「/」不进文档，只开菜单
        if (event.key === "/" && plain) {
          event.preventDefault();
          event.stopPropagation();
          setQuery("");
          setHighlight(0);
        }
        return;
      }
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        setQuery(null);
      } else if (event.key === "ArrowDown") {
        event.preventDefault();
        event.stopPropagation();
        setHighlight((h) => (filtered.length ? (h + 1) % filtered.length : 0));
      } else if (event.key === "ArrowUp") {
        event.preventDefault();
        event.stopPropagation();
        setHighlight((h) => (filtered.length ? (h - 1 + filtered.length) % filtered.length : 0));
      } else if (event.key === "Enter" || event.key === "Tab") {
        event.preventDefault();
        event.stopPropagation();
        if (filtered.length > 0) {
          insertTool(filtered[Math.min(highlight, filtered.length - 1)].name);
        }
      } else if (event.key === "Backspace") {
        event.preventDefault();
        event.stopPropagation();
        if (query.length <= 1) {
          setQuery(null);
        } else {
          setQuery(query.slice(0, -1));
        }
      } else if (event.key.length === 1 && plain) {
        // 可打印字符只用来过滤，不进文档
        event.preventDefault();
        event.stopPropagation();
        setQuery(query + event.key);
      }
    };
    el.addEventListener("keydown", onKeyDown, true);
    return () => el.removeEventListener("keydown", onKeyDown, true);
  }, [filtered, highlight, insertTool, query]);

  const menuVisible = query !== null && filtered.length > 0;

  return (
    <div className="flex h-full min-h-0 flex-col gap-2 text-sm">
      <div className="flex items-center justify-between">
        <span className="text-xs">
          内容（Markdown，所见即所得）
          <span className="text-muted-foreground ml-2">
            输入 <kbd className="rounded border px-1">/</kbd> 插入 MCP / 本地工具名
          </span>
        </span>
        <Button
          type="button"
          variant="outline"
          size="sm"
          data-testid="skill-tool-button"
          onClick={() => {
            setQuery((q) => (q === null ? "" : null));
            setHighlight(0);
          }}
        >
          <Terminal className="size-3.5" />
          插入工具
        </Button>
      </div>

      <div ref={wrapRef} className="relative min-h-0 flex-1" data-testid="skill-wysiwyg">
        <div
          className={cn(
            "border-input mdx-skill-editor h-full overflow-hidden rounded-md border",
            "[&_.mdxeditor-toolbar]:flex-wrap",
          )}
        >
          <MDXEditor
            ref={editorRef}
            markdown={value}
            onChange={onChange}
            placeholder={placeholder}
            contentEditableClassName="prose prose-sm prose-neutral dark:prose-invert max-w-none px-3 py-2 focus:outline-none"
            plugins={[
              headingsPlugin(),
              listsPlugin(),
              quotePlugin(),
              thematicBreakPlugin(),
              linkPlugin(),
              linkDialogPlugin(),
              tablePlugin(),
              codeBlockPlugin({ defaultCodeBlockLanguage: "json" }),
              codeMirrorPlugin({ codeBlockLanguages: CODE_LANGUAGES }),
              markdownShortcutPlugin(),
              diffSourcePlugin({ viewMode: "rich-text" }),
              toolbarPlugin({
                toolbarContents: () => (
                  <DiffSourceToggleWrapper>
                    <UndoRedo />
                    <BlockTypeSelect />
                    <BoldItalicUnderlineToggles />
                    <ListsToggle />
                    <CreateLink />
                    <InsertTable />
                    <InsertCodeBlock />
                    <InsertThematicBreak />
                  </DiffSourceToggleWrapper>
                ),
              }),
            ]}
          />
        </div>

        {menuVisible && (
          <div
            data-testid="skill-tool-menu"
            className="bg-popover absolute right-2 bottom-2 left-2 z-50 max-h-60 overflow-auto rounded-md border shadow-lg"
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
                  insertTool(option.name);
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
    </div>
  );
}
