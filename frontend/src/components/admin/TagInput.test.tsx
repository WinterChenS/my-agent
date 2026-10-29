import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { TagInput, splitTags } from "./TagInput";

/**
 * 白名单用标签输入：用户反馈「让人自己敲英文逗号很容易错」。
 * 这里锁住：多种分隔符都能识别、回车/逗号/失焦都能加、× 能删、留空表示不限制。
 */
describe("splitTags", () => {
  it("英文/中文逗号、分号、空格、换行都当分隔符并去重", () => {
    expect(splitTags("a@b.com, c@d.com;e@f.com，g@h.com； i@j.com\nk@l.com")).toEqual([
      "a@b.com",
      "c@d.com",
      "e@f.com",
      "g@h.com",
      "i@j.com",
      "k@l.com",
    ]);
    expect(splitTags("a@b.com, a@b.com")).toEqual(["a@b.com"]);
    expect(splitTags("")).toEqual([]);
    expect(splitTags(null)).toEqual([]);
  });
});

describe("TagInput", () => {
  it("回车添加一个标签（右侧带删除按钮）", () => {
    const onChange = vi.fn();
    render(<TagInput value="" onChange={onChange} testId="wl" />);

    const input = screen.getByTestId("wl-input");
    fireEvent.change(input, { target: { value: "boss@company.com" } });
    fireEvent.keyDown(input, { key: "Enter" });

    expect(onChange).toHaveBeenCalledWith("boss@company.com");
  });

  it("逗号也能加，并且支持 @域名 这种整域规则", () => {
    const onChange = vi.fn();
    render(<TagInput value="" onChange={onChange} testId="wl" />);

    const input = screen.getByTestId("wl-input");
    fireEvent.change(input, { target: { value: "@partner.com" } });
    fireEvent.keyDown(input, { key: "," });

    expect(onChange).toHaveBeenCalledWith("@partner.com");
  });

  it("已有标签会渲染成 chips，点 × 删除", () => {
    const onChange = vi.fn();
    render(<TagInput value="a@b.com, @partner.com" onChange={onChange} testId="wl" />);

    expect(screen.getByText("a@b.com")).toBeTruthy();
    expect(screen.getByText("@partner.com")).toBeTruthy();

    fireEvent.click(screen.getByLabelText("删除 a@b.com"));
    expect(onChange).toHaveBeenCalledWith("@partner.com");
  });

  it("输入框失焦也会把草稿提交上去（用户常常直接点保存）", () => {
    const onChange = vi.fn();
    render(<TagInput value="" onChange={onChange} testId="wl" />);

    const input = screen.getByTestId("wl-input");
    fireEvent.change(input, { target: { value: "me@company.com" } });
    fireEvent.blur(input);

    expect(onChange).toHaveBeenCalledWith("me@company.com");
  });

  it("空白无内容时不提交（留空=不限制）", () => {
    const onChange = vi.fn();
    render(<TagInput value="" onChange={onChange} testId="wl" />);

    const input = screen.getByTestId("wl-input");
    fireEvent.change(input, { target: { value: "   " } });
    fireEvent.keyDown(input, { key: "Enter" });

    expect(onChange).not.toHaveBeenCalled();
  });

  it("退格删除最后一个标签", () => {
    const onChange = vi.fn();
    render(<TagInput value="a@b.com, c@d.com" onChange={onChange} testId="wl" />);

    fireEvent.keyDown(screen.getByTestId("wl-input"), { key: "Backspace" });

    expect(onChange).toHaveBeenCalledWith("a@b.com");
  });
});
