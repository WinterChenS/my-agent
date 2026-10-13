import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from "react";
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
export interface SkillMdxEditorHandle {
  /** 用代码把正文整段替换掉（例如 AI 生成后回填）。 */
  setMarkdown: (markdown: string) => void;
  getMarkdown: () => string;
}

/**
 * 注意：MDXEditor 的 `markdown` 属性**只当初始值**，父组件改 state 它不会跟着变
 * （官方要求程序化更新走 ref 的 setMarkdown）。
 * 实测后果：AI 生成把 content 写进 state 后，编辑器里还是空的。
 * 所以这里用 forwardRef 暴露 setMarkdown 给父组件调用。
 */
export const SkillMdxEditor = forwardRef<
  SkillMdxEditorHandle,
  {
    value: string;
    onChange: (next: string) => void;
    tools: ToolOption[];
    placeholder?: string;
  }
>(function SkillMdxEditor({ value, onChange, tools, placeholder }, ref) {
  const editorRef = useRef<MDXEditorMethods>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  /** null = 菜单关闭；字符串 = 当前过滤词。 */
  const [query, setQuery] = useState<string | null>(null);
  const [highlight, setHighlight] = useState(0);
  /**
   * 菜单是弹在鼠标底下的，浏览器会立刻给鼠标所在行触发 mouseenter，
   * 于是「回车插哪一条」会**取决于鼠标恰好停在哪**（实测：同一用例时对时错）。
   * 所以菜单刚出现时不响应悬停，等用户**真的移动过鼠标**才启用。
   */
  const hoverArmed = useRef(false);

  // MDXEditor 的 markdown 只当初始值，程序化更新（AI 生成后回填）必须走 setMarkdown
  useImperativeHandle(
    ref,
    () => ({
      setMarkdown: (markdown: string) => editorRef.current?.setMarkdown(markdown),
      getMarkdown: () => editorRef.current?.getMarkdown() ?? "",
    }),
    [],
  );

  /**
   * 候选按来源分组，**本地工具优先**，且每个 MCP 服务器限量。
   *
   * 为什么必须分组：MCP 服务器一多（本机 45 个 × 13 个工具 ≈ 600 条），
   * 直接取前 12 条会**全被同一个服务器的工具占满**，本地工具（read_file / memory_* / agent_* …）
   * 因为排在后面被截断，用户在 `/` 里根本看不到 —— 实测就是这个现象。
   */
  const grouped = useMemo(() => {
    const q = (query ?? "").toLowerCase();
    const matches = tools.filter(
      (t) => !q || t.name.toLowerCase().includes(q) || (t.hint ?? "").toLowerCase().includes(q),
    );
    const byGroup = new Map<string, ToolOption[]>();
    for (const t of matches) {
      const list = byGroup.get(t.group);
      if (list) {
        list.push(t);
      } else {
        byGroup.set(t.group, [t]);
      }
    }
    const isLocal = (g: string) => g.includes("本地");
    const groups = Array.from(byGroup.keys()).sort((a, b) => {
      if (isLocal(a) !== isLocal(b)) {
        return isLocal(a) ? -1 : 1; // 本地工具永远在最前
      }
      return a.localeCompare(b);
    });
    // 本地工具是平台自己的、数量有限（本机 24 个），而且正是写技能时最需要引用的 —— 全部列出；
    // MCP 工具动辄几百个，没输关键字时每组只露前几条，让人接着打字去缩小范围。
    const perGroup = q ? 30 : 6;
    return groups.map((g) => {
      const cap = isLocal(g) && !q ? 200 : perGroup;
      const items = (byGroup.get(g) ?? [])
        .slice()
        .sort((a, b) => a.name.localeCompare(b.name))
        .slice(0, cap);
      return { group: g, items, total: byGroup.get(g)?.length ?? 0 };
    });
  }, [query, tools]);

  /** 扁平化，供键盘上下选择与插入使用。 */
  const flat = useMemo(() => grouped.flatMap((g) => g.items), [grouped]);
  const totalMatches = useMemo(
    () => grouped.reduce((sum, g) => sum + g.total, 0),
    [grouped],
  );

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
        setHighlight((h) => (flat.length ? (h + 1) % flat.length : 0));
      } else if (event.key === "ArrowUp") {
        event.preventDefault();
        event.stopPropagation();
        setHighlight((h) => (flat.length ? (h - 1 + flat.length) % flat.length : 0));
      } else if (event.key === "Enter" || event.key === "Tab") {
        event.preventDefault();
        event.stopPropagation();
        if (flat.length > 0) {
          insertTool(flat[Math.min(highlight, flat.length - 1)].name);
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
  }, [flat, highlight, insertTool, query]);

  const menuVisible = query !== null && flat.length > 0;

  useEffect(() => {
    if (!menuVisible) {
      hoverArmed.current = false;
      return;
    }
    const arm = () => {
      hoverArmed.current = true;
    };
    window.addEventListener("mousemove", arm, { once: true });
    return () => window.removeEventListener("mousemove", arm);
  }, [menuVisible]);

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
            className="bg-popover absolute right-2 bottom-2 left-2 z-50 max-h-72 overflow-auto rounded-md border shadow-lg"
          >
            {grouped.map(({ group, items, total }) => (
              <div key={group}>
                {/* 分组标题：本地工具在最上面，MCP 工具按服务器分组，不会被单个服务器刷屏 */}
                <div
                  data-testid="skill-tool-group"
                  className="bg-muted/60 text-muted-foreground sticky top-0 px-3 py-1 text-[11px] font-medium"
                >
                  {group}
                  <span className="ml-1 font-normal">
                    （{items.length === total ? total : `${items.length} / ${total}`}）
                  </span>
                </div>
                {items.map((option) => {
                  const index = flat.indexOf(option);
                  return (
                    <button
                      key={`${option.group}:${option.name}`}
                      type="button"
                      data-testid="skill-tool-option"
                      className={cn(
                        "flex w-full items-center justify-between gap-2 px-3 py-1.5 text-left text-xs",
                        index === highlight ? "bg-accent" : "hover:bg-accent/60",
                      )}
                      onMouseEnter={() => {
                        if (hoverArmed.current) {
                          setHighlight(index);
                        }
                      }}
                      onMouseDown={(e) => {
                        e.preventDefault();
                        insertTool(option.name);
                      }}
                    >
                      <span className="flex items-center gap-1.5">
                        <Terminal className="size-3 shrink-0 opacity-60" />
                        <code className="font-mono">{option.name}</code>
                      </span>
                      <span className="text-muted-foreground max-w-[45%] truncate">
                        {option.hint ?? ""}
                      </span>
                    </button>
                  );
                })}
              </div>
            ))}
            <div className="text-muted-foreground border-t px-3 py-1 text-[11px]">
              共 {totalMatches} 项匹配
              {totalMatches > flat.length ? "，继续输入可缩小范围" : ""}
              （本地工具排在最前）
            </div>
          </div>
        )}
      </div>
    </div>
  );
});
