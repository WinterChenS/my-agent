import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ConfirmCard } from "./ConfirmCard";
import type { ConfirmCardData } from "@/lib/agui/types";

/**
 * 选择卡：候选答案由 **Agent 给**（用户反馈：以前永远只有「继续/取消」，
 * 想改语气/换方案时没得选）。这里锁住三件事：
 * ① Agent 给的候选都渲染成按钮；② 点哪个就回传哪个（回传给模型的是中文原话）；
 * ③ allowCustom 时能自己写答案。
 */
function card(overrides: Partial<ConfirmCardData> = {}): ConfirmCardData {
  return {
    title: "邮件草稿确认",
    message: "这封问候邮件发出后无法撤回，你希望怎么处理？",
    options: [
      { label: "就这样发送", value: "就这样发送" },
      { label: "更正式", value: "更正式" },
      { label: "更亲切", value: "更亲切" },
    ],
    requestId: "r1",
    ...overrides,
  };
}

describe("ConfirmCard", () => {
  it("把 Agent 给的候选渲染成按钮（不是固定的继续/取消）", () => {
    render(<ConfirmCard data={card()} />);

    expect(screen.getByText("就这样发送")).toBeTruthy();
    expect(screen.getByText("更正式")).toBeTruthy();
    expect(screen.getByText("更亲切")).toBeTruthy();
    expect(screen.queryByText("继续")).toBeNull();
    expect(screen.queryByText("取消")).toBeNull();
  });

  it("点候选回传的是这句候选本身（中文原话，模型能直接理解）", () => {
    const onChoose = vi.fn();
    render(<ConfirmCard data={card()} onChoose={onChoose} />);

    fireEvent.click(screen.getByText("更亲切"));

    expect(onChoose).toHaveBeenCalledWith("更亲切");
  });

  it("只有干/不干时才退回继续/取消（后端给的默认候选）", () => {
    render(
      <ConfirmCard
        data={card({
          title: "需要你的确认",
          options: [
            { label: "继续", value: "继续" },
            { label: "取消", value: "取消" },
          ],
        })}
      />,
    );

    expect(screen.getByText("继续")).toBeTruthy();
    expect(screen.getByText("取消")).toBeTruthy();
  });

  it("allowCustom 时用户可以自己写答案", () => {
    const onChoose = vi.fn();
    render(<ConfirmCard data={card({ allowCustom: true })} onChoose={onChoose} />);

    const input = screen.getByTestId("confirm-card-custom");
    fireEvent.change(input, { target: { value: "改成英文" } });
    fireEvent.click(screen.getByText("就用这个"));

    expect(onChoose).toHaveBeenCalledWith("改成英文");
  });

  it("默认不给输入框；空输入时提交按钮不可用", () => {
    const onChoose = vi.fn();
    render(<ConfirmCard data={card({ allowCustom: true })} onChoose={onChoose} />);

    expect(screen.queryByTestId("confirm-card-custom")).toBeTruthy();
    const submit = screen.getByText("就用这个") as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    fireEvent.click(submit);
    expect(onChoose).not.toHaveBeenCalled();
  });

  it("busy 时候选按钮全部禁用（避免连点重复回传）", () => {
    const onChoose = vi.fn();
    render(<ConfirmCard data={card()} busy onChoose={onChoose} />);

    const button = screen.getByText("更正式") as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    fireEvent.click(button);
    expect(onChoose).not.toHaveBeenCalled();
  });
});
