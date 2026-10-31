import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render } from "@testing-library/react";
import { SkillMentionInput } from "./SkillMentionInput";
import type { SkillOption } from "@/lib/skills/selection";

/**
 * `/` 技能菜单的键盘行为：
 * 1. 高亮项必须跟着滚动（菜单限高，翻到下面几项时要看得见）；
 * 2. 上下循环：第一项按 ↑ 到最后一项，最后一项按 ↓ 回第一项。
 */
function skills(count: number): SkillOption[] {
  return Array.from({ length: count }, (_, index) => ({
    id: index + 1,
    name: `skill-${index}`,
    description: `第 ${index} 个技能`,
  }));
}

function setup(count = 5) {
  const { container } = render(
    <SkillMentionInput parts={[]} onPartsChange={vi.fn()} skills={skills(count)} />,
  );
  const dom = container.querySelector('[contenteditable="true"]') as HTMLElement;
  dom.textContent = "/";
  // 把光标放到 "/" 之后，readQuery 才会认为在输入技能名
  const range = document.createRange();
  range.setStart(dom.firstChild as Node, 1);
  range.collapse(true);
  const selection = window.getSelection();
  selection?.removeAllRanges();
  selection?.addRange(range);
  fireEvent.input(dom);
  return { dom, container };
}

/** 当前高亮的是第几个选项（菜单里带 bg-accent 的那个）。 */
function highlightedIndex(container: HTMLElement): number {
  const options = Array.from(container.querySelectorAll('[data-testid="skill-option"]'));
  // 注意：所有选项的 class 里都有 `hover:bg-accent/60`，必须按**独立类名**判断
  return options.findIndex((node) => node.className.split(/\s+/).includes("bg-accent"));
}

describe("SkillMentionInput：技能菜单滚动与循环", () => {
  beforeEach(() => {
    // jsdom 没有 scrollIntoView，补一个 spy 用来断言"高亮时确实滚了"
    Element.prototype.scrollIntoView = vi.fn();
  });

  it("输入 / 后菜单出现，默认高亮第一项", () => {
    const { container } = setup();
    expect(container.querySelector('[data-testid="skill-menu"]')).toBeTruthy();
    expect(highlightedIndex(container)).toBe(0);
  });

  it("第一项按 ↑ 跳到最后一个（循环）", () => {
    const { dom, container } = setup();
    fireEvent.keyDown(dom, { key: "ArrowUp" });
    expect(highlightedIndex(container)).toBe(4);
  });

  it("最后一项按 ↓ 回到第一项（循环）", () => {
    const { dom, container } = setup();
    for (let i = 0; i < 5; i += 1) {
      fireEvent.keyDown(dom, { key: "ArrowDown" });
    }
    expect(highlightedIndex(container)).toBe(0);
  });

  it("高亮变化时会让选中项滚进可视区", () => {
    const { dom } = setup();
    const spy = Element.prototype.scrollIntoView as unknown as ReturnType<typeof vi.fn>;
    spy.mockClear();
    fireEvent.keyDown(dom, { key: "ArrowDown" });
    expect(spy).toHaveBeenCalled();
    expect(spy).toHaveBeenCalledWith({ block: "nearest" });
  });
});
