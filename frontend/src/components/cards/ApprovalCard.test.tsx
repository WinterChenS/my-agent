import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ApprovalCard } from "./ApprovalCard";

/**
 * 审批卡：Agent 还在输出时（busy=true）也必须能点。
 * 以前用父组件的 busy 禁用按钮 → 用户体感"卡片卡死了，要等 Agent 说完"（实测反馈）。
 */
describe("ApprovalCard", () => {
  const base = {
    toolName: "mail_send",
    args: { to: "a@b.com" },
    message: "将以你的身份发送邮件",
    status: "pending" as const,
  };

  it("busy（Agent 还在输出）时依然可以批准/拒绝", () => {
    const onDecide = vi.fn();
    render(<ApprovalCard {...base} busy onDecide={onDecide} />);

    const approve = screen.getByText("批准并继续") as HTMLButtonElement;
    expect(approve.disabled).toBe(false);
    fireEvent.click(approve);
    expect(onDecide).toHaveBeenCalledWith("APPROVED");
  });

  it("点过一次后按钮变灰，避免连点重复提交", () => {
    const onDecide = vi.fn();
    render(<ApprovalCard {...base} onDecide={onDecide} />);

    fireEvent.click(screen.getByText("拒绝"));
    expect(onDecide).toHaveBeenCalledWith("DENIED");
    expect((screen.getByText("拒绝") as HTMLButtonElement).disabled).toBe(true);
  });

  it("已决策的卡片不再显示按钮", () => {
    render(<ApprovalCard {...base} status="approved" />);
    expect(screen.queryByText("批准并继续")).toBeNull();
    expect(screen.getByText("已批准")).toBeTruthy();
  });
});
