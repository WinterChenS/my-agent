import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { SubagentCard } from "./SubagentCard";
import type { SubagentItem } from "@/lib/agui/types";

function makeItem(overrides: Partial<SubagentItem> = {}): SubagentItem {
  return {
    kind: "subagent",
    id: "subagent-t1/gp",
    source: "t1/gp",
    name: "gp",
    status: "done",
    text: "",
    thinking: "",
    toolCalls: [],
    steps: [],
    confirm: null,
    ...overrides,
  };
}

describe("SubagentCard", () => {
  it("收起时只显示一行摘要（名称 + 状态 + 工具调用次数）", () => {
    render(
      <SubagentCard
        item={makeItem({
          steps: [
            { kind: "thinking", text: "想" },
            { kind: "tool", name: "read_file", done: true },
          ],
          toolCalls: [{ name: "read_file", done: true }],
        })}
      />,
    );
    expect(screen.getByTestId("subagent-card")).toBeInTheDocument();
    expect(screen.getByText(/1 次工具调用/)).toBeInTheDocument();
    // 收起态不渲染步骤
    expect(screen.queryByTestId("subagent-step-tool")).not.toBeInTheDocument();
  });

  it("展开后按 steps 的**时间顺序**渲染（思考 → 工具 → 正文 → 思考 → 工具）", () => {
    render(
      <SubagentCard
        item={makeItem({
          steps: [
            { kind: "thinking", text: "先想" },
            { kind: "tool", name: "read_file", done: true },
            { kind: "text", text: "读到了" },
            { kind: "thinking", text: "再想" },
            { kind: "tool", name: "grep", done: false },
          ],
        })}
      />,
    );
    fireEvent.click(screen.getByRole("button"));

    const steps = within(screen.getByTestId("subagent-steps"));
    const order = steps
      .queryAllByTestId(/subagent-step-/)
      .map((el) => el.getAttribute("data-testid"));
    expect(order).toEqual([
      "subagent-step-thinking",
      "subagent-step-tool",
      "subagent-step-text",
      "subagent-step-thinking",
      "subagent-step-tool",
    ]);
  });

  it("老数据没有 steps 时退化用 text/thinking/toolCalls 也能渲染", () => {
    render(
      <SubagentCard
        item={makeItem({
          thinking: "老思考",
          text: "老正文",
          toolCalls: [{ name: "grep", done: true }],
        })}
      />,
    );
    fireEvent.click(screen.getByRole("button"));
    expect(screen.getByTestId("subagent-step-thinking")).toHaveTextContent("老思考");
    expect(screen.getByTestId("subagent-step-text")).toHaveTextContent("老正文");
    expect(screen.getByTestId("subagent-step-tool")).toHaveTextContent("grep");
  });
});

/** testing-library 的 within 便捷包装（只在本文件用，允许匹配正则 testid）。 */
function within(container: HTMLElement) {
  return {
    queryAllByTestId(pattern: RegExp) {
      return Array.from(container.querySelectorAll("[data-testid]")).filter((el) => {
        const value = el.getAttribute("data-testid") ?? "";
        return pattern.test(value);
      });
    },
  };
}
