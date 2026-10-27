import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { PendingIndicator } from "@/components/chat/PendingBubble";

/**
 * 「正在思考」提示：**不是气泡**。
 *
 * <p>用户实测：空气泡占位 + 思考/工具插在下面 + 正文又补进上面那条气泡 → 顺序错乱。
 * 现在改成一行浅色提示，不占消息流位置。
 */
describe("PendingIndicator：轻量提示而非气泡", () => {
  it("渲染文案与状态语义", () => {
    render(<PendingIndicator />);

    const node = screen.getByTestId("pending-bubble");
    expect(node.textContent).toContain("正在思考…");
    expect(node.getAttribute("role")).toBe("status");
    // 无障碍：让读屏软件知道这是"进行中"的提示
    expect(node.getAttribute("aria-live")).toBe("polite");
  });

  it("没有气泡的外观（无边框/无卡片底色）", () => {
    render(<PendingIndicator />);

    const node = screen.getByTestId("pending-bubble");
    const classes = node.className;
    expect(classes).not.toContain("rounded-2xl");
    expect(classes).not.toContain("border");
    expect(classes).not.toContain("bg-card");
    expect(classes).not.toContain("shadow");
  });

  it("支持自定义文案", () => {
    render(<PendingIndicator label="正在生成…" />);

    expect(screen.getByText("正在生成…")).toBeTruthy();
  });
});
