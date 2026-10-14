import { useCallback, useEffect, useMemo, useRef, useState } from "react";
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
export function SkillMentionInput({
  parts,
  onPartsChange,
  skills,
  disabled,
  placeholder,
  onSubmit,
  onPasteFiles,
}: {
  parts: MentionPart[];
  onPartsChange: (next: MentionPart[]) => void;
  skills: SkillOption[];
  disabled?: boolean;
  placeholder?: string;
  /** 回车（不带 shift）时提交。 */
  onSubmit: () => void;
  /** 粘贴里的文件交给父组件去暂存。 */
  onPasteFiles: (files: File[]) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const composingRef = useRef(false);
  const [query, setQuery] = useState<string | null>(null);
  const [highlight, setHighlight] = useState(0);
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

  /** 把 DOM 序列化成 parts。 */
  const serialize = useCallback((): MentionPart[] => {
    const root = ref.current;
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
    lastHtml.current = ref.current?.innerHTML ?? "";
    onPartsChange(next);
  }, [onPartsChange, serialize]);

  /** parts → DOM（只在外部改动时同步，输入过程中不重写）。 */
  useEffect(() => {
    const root = ref.current;
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
      const root = ref.current;
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
        ref={ref}
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
          "border-input chat-input box-border max-h-40 min-h-9 w-full overflow-y-auto rounded-md border bg-transparent px-3 py-2 text-sm leading-6 break-words whitespace-pre-wrap",
          "focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] focus-visible:outline-none",
          disabled && "cursor-not-allowed opacity-50",
        )}
        onClick={onContainerClick}
        onCompositionStart={() => {
          composingRef.current = true;
        }}
        onCompositionEnd={() => {
          composingRef.current = false;
        }}
        onInput={() => {
          if (composingRef.current) {
            return;
          }
          setQuery(readQuery());
          emit();
        }}
        onKeyDown={(event) => {
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
          data-testid="skill-menu"
          className="bg-popover absolute bottom-full left-0 z-20 mb-1 max-h-56 w-72 overflow-auto rounded-md border shadow-md"
        >
          {options.length === 0 && <p className="text-muted-foreground p-2 text-xs">没有匹配的技能</p>}
          {options.map((skill, index) => (
            <button
              key={skill.id}
              type="button"
              data-testid="skill-option"
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
}

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
