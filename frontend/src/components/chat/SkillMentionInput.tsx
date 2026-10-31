import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from "react";
import { cn } from "@/lib/utils";
import {
  filterSkills,
  skillsQuery,
  type MentionPart,
  type SkillOption,
} from "@/lib/skills/selection";

/**
 * 带「技能块」的聊天输入框（行内）。
 *
 * 为什么用 contentEditable 而不是 <input>：
 * 用户要求技能是**不能编辑的块**，而且要**显示在它当时所在的位置**（随消息一起进气泡）。
 * <input> 里没法放不可编辑的内联块，所以这里用 contentEditable：
 *   · 普通文字正常输入；
 *   · 技能是一个 `contenteditable="false"` 的 span（点 × 删除，不能改字）；
 *   · 打 `/` 弹候选（候选项与过滤逻辑复用 lib/skills/selection 的纯函数）。
 *
 * 对外只暴露 parts（文本段 + 技能块），序列化成 `[[skill:名字]]` 交给后端，
 * 这样后端/模型能看到「这一句里用了哪个技能」，气泡也能按原位置还原。
 */
export interface SkillMentionInputHandle {
  /**
   * 把编辑器**当前 DOM** 的内容同步给父组件，并返回它。
   *
   * 用途：发送前调用一次，确保「发出去的就是屏幕上看到的」——
   * 组字（中文输入法）期间 parts 会滞后，不 flush 就可能少发内容。
   */
  flush: () => MentionPart[];
  /**
   * 立刻清空编辑器（发送后调用）。
   *
   * 为什么不只依赖"父组件把 parts 置空、由 effect 回写"：
   * 在同一轮事件里连续 setParts（flush 一次、清空一次）时 React 会**批处理**成一次渲染，
   * 于是"自己输入不回写"的标记还没被消费掉 → effect 直接跳过，**输入框残留文字**（实测用户反馈）。
   * 所以清空要显式、立即做，不看渲染时序。
   */
  clear: () => void;
}

export const SkillMentionInput = forwardRef<
  SkillMentionInputHandle,
  {
    parts: MentionPart[];
    onPartsChange: (next: MentionPart[]) => void;
    skills: SkillOption[];
    disabled?: boolean;
    placeholder?: string;
    onSubmit: () => void;
    onPasteFiles: (files: File[]) => void;
    onContentChange?: (hasContent: boolean) => void;
    /**
     * 只要输入区、不要它自己的边框/圆角/聚焦环。
     *
     * 用途：外层已经有一个圆角框（聊天输入区就是），此时内层再画一圈会变成
     * **双层边框 + 中间一条线**，视觉上很脏（用户反馈）。边框与聚焦环统一由外层负责。
     */
    bare?: boolean;
  }
>(function SkillMentionInput(
  { parts, onPartsChange, skills, disabled, placeholder, onSubmit, onPasteFiles, onContentChange, bare },
  ref,
) {
  const domRef = useRef<HTMLDivElement>(null);
  const composingRef = useRef(false);
  const [query, setQuery] = useState<string | null>(null);
  const [highlight, setHighlight] = useState(0);
  /** 菜单容器与每个选项：键盘移动高亮时要**跟着滚动**，否则下面的选项看不见（用户反馈）。 */
  const menuRef = useRef<HTMLDivElement | null>(null);
  const optionRefs = useRef<(HTMLButtonElement | null)[]>([]);
  /** 外部内容变化时才重写 DOM（避免打断输入与中文输入法）。 */
  const lastHtml = useRef<string | null>(null);
  /**
   * 本次 parts 变化是否由**用户输入**引起。
   *
   * 踩过的坑：一开始靠「序列化结果 === innerHTML」判断要不要重写，
   * 但序列化时 nbsp 是字面字符、而浏览器 innerHTML 写成 `&nbsp;`，两者永不相等 →
   * **每次按键都重写 DOM → 光标跳回开头**，实测「帮我看看」被打成了「看看我帮」。
   * 所以改成显式标记：自己输入引起的变化不重写 DOM，只有外部改动（发送后清空/失败恢复）才写。
   */
  const selfUpdate = useRef(false);

  const selected = useMemo(
    () => parts.filter((p) => p.type === "skill").map((p) => ({ id: p.id, name: p.name })),
    [parts],
  );
  const options = useMemo(
    () => filterSkills(skills, query ?? "", selected),
    [skills, query, selected],
  );

  // 输入变化 → 高亮回到第一项（否则筛选后高亮可能越界，看起来"没有选中项"）
  useEffect(() => {
    setHighlight(0);
  }, [query]);

  /**
   * 高亮项始终在菜单可视区内。
   *
   * <p>为什么需要：菜单限高 `max-h-56`，用方向键翻到下面几项时列表不会跟着滚，
   * 高亮的那一项就跑到可视区外了 —— 用户只看到"按了没反应"（实测反馈）。
   */
  useEffect(() => {
    const option = optionRefs.current[highlight];
    // jsdom 里没有 scrollIntoView，测试环境要容错
    option?.scrollIntoView?.({ block: "nearest" });
  }, [highlight, options.length]);

  /** 把 DOM 序列化成 parts。 */
  const serialize = useCallback((): MentionPart[] => {
    const root = domRef.current;
    if (!root) {
      return [];
    }
    const out: MentionPart[] = [];
    const walk = (node: Node) => {
      if (node.nodeType === Node.TEXT_NODE) {
        const text = node.textContent ?? "";
        if (text) {
          out.push({ type: "text", text });
        }
        return;
      }
      if (node instanceof HTMLElement && node.dataset.skillName) {
        out.push({
          type: "skill",
          id: Number(node.dataset.skillId ?? "-1"),
          name: node.dataset.skillName,
        });
        return;
      }
      node.childNodes.forEach(walk);
    };
    root.childNodes.forEach(walk);
    return out;
  }, []);

  const emit = useCallback(() => {
    const next = serialize();
    selfUpdate.current = true;
    lastHtml.current = domRef.current?.innerHTML ?? "";
    onPartsChange(next);
    // 以 DOM 为准上报"有没有内容"：比 parts 更及时（组字期间的文本不在 parts 里）
    const dom = domRef.current;
    const hasContent = !!dom && (dom.textContent?.trim() !== "" || !!dom.querySelector("[data-skill-name]"));
    onContentChange?.(hasContent);
  }, [onPartsChange, onContentChange, serialize]);

  // 发送前由父组件调用：把 DOM 现状回灌进 parts，保证「发出去 == 屏幕上看到的」
  useImperativeHandle(
    ref,
    () => ({
      flush: () => {
        const next = serialize();
        lastHtml.current = domRef.current?.innerHTML ?? "";
        onPartsChange(next);
        return next;
      },
      clear: () => {
        // 立即清 DOM（不靠 effect 回写），并同步状态
        if (domRef.current) {
          domRef.current.innerHTML = "";
        }
        selfUpdate.current = false;
        lastHtml.current = "";
        onPartsChange([]);
        onContentChange?.(false);
      },
    }),
    [onPartsChange, onContentChange, serialize],
  );

  /** parts → DOM（只在外部改动时同步，输入过程中不重写）。 */
  useEffect(() => {
    const root = domRef.current;
    if (!root) {
      return;
    }
    if (selfUpdate.current) {
      // 用户正在输入：DOM 才是事实来源，绝不回写（否则光标会跳）
      selfUpdate.current = false;
      return;
    }
    const wanted = partsToHtml(parts);
    if (wanted === root.innerHTML) {
      return;
    }
    root.innerHTML = wanted;
    lastHtml.current = wanted;
  }, [parts]);

  /** 读光标前的 `/关键字`（用于弹候选）。 */
  const readQuery = useCallback((): string | null => {
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0) {
      return null;
    }
    const range = sel.getRangeAt(0);
    if (!range.collapsed || range.startContainer.nodeType !== Node.TEXT_NODE) {
      return null;
    }
    const before = (range.startContainer.textContent ?? "").slice(0, range.startOffset);
    // 复用纯函数：只有在行首/空白后的 `/xxx` 才算
    return skillsQuery(before);
  }, []);

  /** 在光标处插入技能块（并删掉触发菜单的 `/关键字`）。 */
  const insertSkill = useCallback(
    (skill: SkillOption) => {
      const sel = window.getSelection();
      const root = domRef.current;
      if (!sel || sel.rangeCount === 0 || !root) {
        return;
      }
      const range = sel.getRangeAt(0);
      // 先删掉 `/关键字`
      const node = range.startContainer;
      if (node.nodeType === Node.TEXT_NODE) {
        const before = (node.textContent ?? "").slice(0, range.startOffset);
        const idx = before.lastIndexOf("/");
        if (idx >= 0) {
          const removal = document.createRange();
          removal.setStart(node, idx);
          removal.setEnd(node, range.startOffset);
          removal.deleteContents();
          removal.collapse(true);
          sel.removeAllRanges();
          sel.addRange(removal);
        }
      }
      // 再插入块 + 一个空格
      const chip = document.createElement("span");
      chip.contentEditable = "false";
      chip.dataset.skillId = String(skill.id);
      chip.dataset.skillName = skill.name;
      chip.className =
        "mx-0.5 inline-flex items-center gap-1 rounded bg-secondary px-1.5 py-0.5 align-middle text-[11px] font-medium text-secondary-foreground";
      chip.title = "技能（不可编辑，点 × 删除）";
      const label = document.createElement("span");
      label.textContent = "/" + skill.name;
      const close = document.createElement("span");
      close.dataset.skillRemove = "1";
      close.className = "cursor-pointer opacity-70 hover:opacity-100";
      close.textContent = "×";
      chip.append(label, close);

      const active = window.getSelection();
      const insertRange = active && active.rangeCount > 0 ? active.getRangeAt(0) : null;
      if (!insertRange) {
        return;
      }
      insertRange.insertNode(chip);
      const space = document.createTextNode("\u00a0");
      chip.after(space);
      insertRange.setStartAfter(space);
      insertRange.collapse(true);
      active?.removeAllRanges();
      active?.addRange(insertRange);
      setQuery(null);
      emit();
    },
    [emit],
  );

  /** 点击 × 删除技能块。 */
  const onContainerClick = useCallback(
    (event: React.MouseEvent<HTMLDivElement>) => {
      const target = event.target as HTMLElement;
      if (!target.dataset.skillRemove) {
        return;
      }
      const chip = target.closest<HTMLElement>("[data-skill-name]");
      chip?.remove();
      emit();
    },
    [emit],
  );

  return (
    <div className="relative min-w-0 flex-1">
      <div
        ref={domRef}
        role="textbox"
        aria-multiline="true"
        aria-label={placeholder}
        // placeholder 属性 + CSS :empty:before 让 contentEditable 也有占位提示
        // （保留它同时兼容既有 e2e 用 getByPlaceholder 定位输入框）
        {...{ placeholder }}
        data-placeholder={placeholder}
        data-testid="chat-input"
        contentEditable={!disabled}
        suppressContentEditableWarning
        className={cn(
          "chat-input box-border max-h-40 min-h-9 w-full overflow-y-auto bg-transparent px-3 py-2 text-sm leading-6 break-words whitespace-pre-wrap focus-visible:outline-none",
          // bare：边框/圆角/聚焦环交给外层容器（避免双层边框、中间出现一条线）
          bare
            ? "rounded-none border-0 focus-visible:ring-0"
            : "border-input rounded-md border focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]",
          disabled && "cursor-not-allowed opacity-50",
        )}
        onClick={onContainerClick}
        onCompositionStart={() => {
          composingRef.current = true;
        }}
        onCompositionEnd={() => {
          composingRef.current = false;
          // 上屏后必须同步一次：组字期间 onInput 是跳过的，不补这一下，
          // parts 会停留在上屏前的状态（发送键据此判断可用性，就会显示成灰的）
          setQuery(readQuery());
          emit();
        }}
        onInput={() => {
          if (composingRef.current) {
            // 组字中：不进 parts（避免半成品进状态），但**要**告诉父组件"有内容"，
            // 否则发送键在组字期间一直是灰的
            const dom = domRef.current;
            onContentChange?.(
              !!dom && (dom.textContent?.trim() !== "" || !!dom.querySelector("[data-skill-name]")),
            );
            return;
          }
          setQuery(readQuery());
          emit();
        }}
        onKeyDown={(event) => {
          // 关键：中文输入法组字期间（比如打「导出excel」时 excel 还没上屏），
          // Enter/方向键/Tab 都是**输入法在用**（上屏、选词）。抢走它们会导致：
          // ①组字内容没上屏就被当成"发送"，于是只发出已上屏的部分（实测：发「导出excel」只出去「导出」）；
          // ②状态与界面不一致，发送键看起来还是灰的。
          // isComposing 在合成事件的 keydown 上为 true；keyCode 229 是旧浏览器的等价信号。
          if (event.nativeEvent.isComposing || composingRef.current || event.keyCode === 229) {
            return;
          }
          const menuOpen = query !== null && options.length > 0;
          if (menuOpen) {
            if (event.key === "ArrowDown") {
              event.preventDefault();
              setHighlight((prev) => (prev + 1) % options.length);
              return;
            }
            if (event.key === "ArrowUp") {
              event.preventDefault();
              setHighlight((prev) => (prev - 1 + options.length) % options.length);
              return;
            }
            if (event.key === "Enter" || event.key === "Tab") {
              event.preventDefault();
              insertSkill(options[Math.min(highlight, options.length - 1)]);
              return;
            }
            if (event.key === "Escape") {
              event.preventDefault();
              setQuery(null);
              return;
            }
          }
          if (event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            onSubmit();
          }
        }}
        onPaste={(event) => {
          const files = Array.from(event.clipboardData.files ?? []);
          if (files.length > 0) {
            event.preventDefault();
            onPasteFiles(files);
            return;
          }
          // 纯文本粘贴：去掉富文本格式，只留文本
          const text = event.clipboardData.getData("text/plain");
          if (text) {
            event.preventDefault();
            document.execCommand("insertText", false, text);
            emit();
          }
        }}
        onBlur={() => window.setTimeout(() => setQuery(null), 150)}
      />

      {query !== null && (
        <div
          ref={menuRef}
          data-testid="skill-menu"
          className="bg-popover absolute bottom-full left-0 z-20 mb-1 max-h-56 w-72 overflow-auto rounded-md border shadow-md"
        >
          {options.length === 0 && <p className="text-muted-foreground p-2 text-xs">没有匹配的技能</p>}
          {options.map((skill, index) => (
            <button
              key={skill.id}
              type="button"
              data-testid="skill-option"
              ref={(node) => {
                optionRefs.current[index] = node;
              }}
              onMouseEnter={() => setHighlight(index)}
              className={cn(
                "flex w-full flex-col items-start gap-0.5 px-2 py-1.5 text-left",
                index === highlight ? "bg-accent" : "hover:bg-accent/60",
              )}
              onMouseDown={(e) => {
                e.preventDefault();
                insertSkill(skill);
              }}
            >
              <span className="text-xs font-medium">/{skill.name}</span>
              {skill.description && (
                <span className="text-muted-foreground line-clamp-1 text-[11px]">
                  {skill.description}
                </span>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
});

/** parts → contentEditable 的 HTML。技能用 data-* 标记，删除按钮用 data-skill-remove。 */
function partsToHtml(parts: MentionPart[]): string {
  return parts
    .map((part) => {
      if (part.type === "text") {
        return escapeHtml(part.text);
      }
      return (
        `<span contenteditable="false" data-skill-id="${part.id}" data-skill-name="${escapeHtml(
          part.name,
        )}" title="技能（不可编辑，点 × 删除）" class="mx-0.5 inline-flex items-center gap-1 rounded bg-secondary px-1.5 py-0.5 align-middle text-[11px] font-medium text-secondary-foreground">` +
        `<span>/${escapeHtml(part.name)}</span>` +
        `<span data-skill-remove="1" class="cursor-pointer opacity-70 hover:opacity-100">×</span>` +
        `</span>`
      );
    })
    .join("");
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
