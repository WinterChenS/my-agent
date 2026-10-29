import { useState } from "react";
import { X } from "lucide-react";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

/**
 * 标签输入（白名单用）：回车 / 逗号 / 分号 添加，点 × 删除。
 *
 * 为什么要有它：发信白名单、自动回复白名单这类字段是"一组地址"，
 * 让用户在一行文本框里自己敲英文逗号很容易出错（中文逗号、多敲空格、看不清有几个）。
 * 存储仍是逗号分隔的字符串，和后端保持一致（`a@b.com, @公司.com`）。
 *
 * 语义统一：**留空 = 不限制**（发信=谁都能发；自动回复=对所有人都回）。
 */
export function TagInput({
  value,
  onChange,
  placeholder,
  testId,
}: {
  /** 逗号分隔的原始值（直接对应后端存储）。 */
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  testId?: string;
}) {
  const [draft, setDraft] = useState("");
  const tags = splitTags(value);

  const commit = (raw: string) => {
    const next = splitTags(raw).filter((item) => !tags.includes(item));
    // 空白/全是重复项时不触发 onChange：否则"留空=不限制"会被一次无意义的写入变成空串，
    // 用户点保存时反而把已有白名单清掉了（草稿框失焦也会走到这里）。
    if (next.length === 0) {
      setDraft("");
      return;
    }
    onChange([...tags, ...next].join(", "));
    setDraft("");
  };

  const handleKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter" || event.key === "," || event.key === ";" || event.key === "，") {
      event.preventDefault();
      commit(draft);
    } else if (event.key === "Backspace" && !draft && tags.length > 0) {
      // 输入框空着按退格 → 删掉最后一个（常见交互习惯）
      event.preventDefault();
      onChange(tags.slice(0, -1).join(", "));
    }
  };

  return (
    <div
      className={cn(
        "border-input flex min-h-9 w-full flex-wrap items-center gap-1 rounded-md border bg-transparent px-2 py-1",
      )}
      data-testid={testId}
    >
      {tags.map((tag) => (
        <span
          key={tag}
          className="bg-secondary text-secondary-foreground inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-xs"
          data-testid={testId ? `${testId}-tag` : undefined}
        >
          {tag}
          <button
            type="button"
            className="hover:text-destructive"
            aria-label={`删除 ${tag}`}
            onClick={() => onChange(tags.filter((item) => item !== tag).join(", "))}
          >
            <X className="size-3" />
          </button>
        </span>
      ))}
      <Input
        value={draft}
        placeholder={tags.length === 0 ? placeholder : "继续添加…"}
        className="h-7 flex-1 border-0 px-1 shadow-none focus-visible:ring-0"
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={handleKeyDown}
        onBlur={() => commit(draft)}
        data-testid={testId ? `${testId}-input` : undefined}
      />
    </div>
  );
}

/** 兼容中文逗号/分号/换行/多空格：都当分隔符，去空去重。 */
export function splitTags(value: string | null | undefined): string[] {
  if (!value) {
    return [];
  }
  return Array.from(
    new Set(
      value
        .split(/[,;，；\s]+/)
        .map((item) => item.trim())
        .filter((item) => item.length > 0),
    ),
  );
}
