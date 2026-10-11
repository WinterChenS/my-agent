import { X } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { SelectedSkill, SkillOption } from "@/lib/skills/selection";

interface Props {
  /** 选中的技能：以「不可编辑的块」呈现，只能整块删除（Phase 10 / Task 10.5）。 */
  selected: SelectedSkill[];
  /** 输入 `/` 后的候选列表；null 表示不展示菜单。 */
  query: string | null;
  options: SkillOption[];
  highlight: number;
  onSelect: (skill: SkillOption) => void;
  onRemove: (id: number) => void;
  onHighlight: (index: number) => void;
}

/**
 * `/` 技能选择器。
 *
 * <p>选中后不进入输入框文本，而是变成一个独立的块（chip）——这样用户不会误改技能名，
 * 也能明确看到本轮用了哪些技能；再次输入 `/` 可以继续追加，重复的不会出现。
 */
export function SkillPicker({
  selected,
  query,
  options,
  highlight,
  onSelect,
  onRemove,
  onHighlight,
}: Props) {
  return (
    <>
      {selected.length === 0 && query === null && (
        <p className="text-muted-foreground mb-1 text-[11px]">输入 / 可选择技能，选中的技能会变成可删除的块</p>
      )}

      {selected.length > 0 && (
        <div className="mb-2 flex flex-wrap gap-1" data-testid="skill-blocks">
          {selected.map((skill) => (
            <span
              key={skill.id}
              data-testid="skill-block"
              className="bg-secondary text-secondary-foreground inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs"
            >
              <span className="font-medium">/{skill.name}</span>
              <button
                type="button"
                aria-label={`删除技能 ${skill.name}`}
                title="删除技能"
                className="hover:text-destructive"
                onClick={() => onRemove(skill.id)}
              >
                <X className="size-3" />
              </button>
            </span>
          ))}
        </div>
      )}

      {query !== null && (
        <div
          data-testid="skill-menu"
          className="bg-popover absolute bottom-full left-0 z-10 mb-1 max-h-56 w-72 overflow-auto rounded-md border shadow-md"
        >
          {options.length === 0 && (
            <p className="text-muted-foreground p-2 text-xs">没有匹配的技能</p>
          )}
          {options.map((skill, index) => (
            <Button
              key={skill.id}
              type="button"
              variant="ghost"
              data-testid="skill-option"
              className={`h-auto w-full flex-col items-start gap-0.5 rounded-none px-2 py-1.5 ${
                index === highlight ? "bg-accent" : ""
              }`}
              onMouseEnter={() => onHighlight(index)}
              onClick={() => onSelect(skill)}
            >
              <span className="text-xs font-medium">/{skill.name}</span>
              {skill.description && (
                <span className="text-muted-foreground line-clamp-1 text-[11px]">
                  {skill.description}
                </span>
              )}
            </Button>
          ))}
        </div>
      )}
    </>
  );
}
