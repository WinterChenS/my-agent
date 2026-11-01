import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ThinkingBlock } from "@/components/chat/ThinkingBlock";
import type { ChatItem } from "@/lib/agui/types";

type ThinkingItem = Extract<ChatItem, { kind: "thinking" }>;

function item(content: string, streaming: boolean): ThinkingItem {
  return { kind: "thinking", id: "thinking-1", content, streaming };
}

/**
 * 思考块的交互约定（用户明确要求）：
 * 思考中展开 → 结束自动折叠 → 还能点开回看。
 *
 * <p>为什么值得单独测：折叠态是组件本地状态 + 「streaming 由真变假」的副作用，
 * 这类"只在真实流式过程中出现一次"的行为，光靠手点是很难复现的。
 */
describe("ThinkingBlock：思考中展开、结束折叠", () => {
  it("流式中默认展开并显示内容", () => {
    render(<ThinkingBlock item={item("先看需求", true)} />);

    expect(screen.getByText("正在思考…")).toBeTruthy();
    expect(screen.getByTestId("thinking-content").textContent).toContain("先看需求");
  });

  it("思考结束自动折叠成一行摘要，点一下能再展开", () => {
    const { rerender } = render(<ThinkingBlock item={item("先看需求", true)} />);

    // 流式结束（同一个组件实例，streaming 变 false）
    rerender(<ThinkingBlock item={item("先看需求", false)} />);

    expect(screen.getByText("已思考（4 字）")).toBeTruthy();
    // 折叠改成 Grid 0fr（DOM 保留以便丝滑过渡），用 data-open 断言状态
    expect(screen.getByTestId("thinking-collapse")).toHaveAttribute("data-open", "false");

    fireEvent.click(screen.getByTestId("thinking-toggle"));
    expect(screen.getByTestId("thinking-content").textContent).toContain("先看需求");
  });

  it("历史回放（初始就是已结束）默认折叠，点一下能展开", () => {
    render(<ThinkingBlock item={item("当时的思考", false)} />);

    expect(screen.getByText("已思考（5 字）")).toBeTruthy();
    expect(screen.getByTestId("thinking-collapse")).toHaveAttribute("data-open", "false");

    fireEvent.click(screen.getByTestId("thinking-toggle"));
    expect(screen.getByTestId("thinking-content").textContent).toContain("当时的思考");
  });

  it("内容随增量增长（同一个 streaming 状态下重渲染）", () => {
    const { rerender } = render(<ThinkingBlock item={item("第一步", true)} />);
    rerender(<ThinkingBlock item={item("第一步，第二步", true)} />);

    expect(screen.getByTestId("thinking-content").textContent).toContain("第二步");
  });
});
