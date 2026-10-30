import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SliderCaptcha } from "./SliderCaptcha";

const fetchCaptcha = vi.fn();
const verifyCaptcha = vi.fn();
vi.mock("@/api/auth", () => ({
  fetchCaptcha: () => fetchCaptcha(),
  verifyCaptcha: (id: string, x: number) => verifyCaptcha(id, x),
}));

function challenge(overrides: Record<string, unknown> = {}) {
  return {
    required: true,
    id: "c1",
    background: "data:image/png;base64,AAA",
    piece: "data:image/png;base64,BBB",
    pieceY: 40,
    pieceSize: 48,
    width: 320,
    height: 160,
    ttlSeconds: 120,
    ...overrides,
  };
}

describe("SliderCaptcha 滑动验证", () => {
  beforeEach(() => {
    fetchCaptcha.mockReset();
    verifyCaptcha.mockReset();
  });

  it("服务端关掉验证时整块不渲染，并把 null 交给登录表单", async () => {
    fetchCaptcha.mockResolvedValue(challenge({ required: false }));
    const onVerified = vi.fn();
    const { container } = render(<SliderCaptcha onVerified={onVerified} />);
    await waitFor(() => expect(onVerified).toHaveBeenCalledWith(null));
    expect(container).toBeEmptyDOMElement();
  });

  it("拼图平时不展示（不占位），按下滑块才浮出来，校验完自动收起", async () => {
    fetchCaptcha.mockResolvedValue(challenge());
    verifyCaptcha.mockResolvedValue({ ticket: "t-0" });
    render(<SliderCaptcha onVerified={vi.fn()} />);

    const handle = await screen.findByTestId("captcha-handle");
    // 平时：面板 invisible（absolute 定位，不挤占密码框和按钮的位置）
    const panel = screen.getByTestId("captcha-panel");
    expect(panel.className).toContain("invisible");
    expect(panel.getAttribute("data-open")).toBe("false");
    expect(panel.className).toContain("absolute");

    // 按下 → 浮出
    fireEvent.mouseDown(handle);
    expect(screen.getByTestId("captcha-panel").getAttribute("data-open")).toBe("true");
    expect(screen.getByTestId("captcha-panel").className).not.toContain("invisible");

    fireEvent.mouseMove(window, { clientX: 100 });
    fireEvent.mouseUp(window);
    // 松手校验完，短暂展示结果后自动收起
    await waitFor(() => expect(verifyCaptcha).toHaveBeenCalledTimes(1));
    await waitFor(
      () => expect(screen.getByTestId("captcha-panel").getAttribute("data-open")).toBe("false"),
      { timeout: 3000 },
    );
  });

  it("拖动滑块松手后校验通过：回调拿到 ticket，题目锁住", async () => {
    fetchCaptcha.mockResolvedValue(challenge());
    verifyCaptcha.mockResolvedValue({ ticket: "t-1" });
    const onVerified = vi.fn();
    render(<SliderCaptcha onVerified={onVerified} />);

    const handle = await screen.findByTestId("captcha-handle");
    fireEvent.mouseDown(handle);
    fireEvent.mouseMove(window, { clientX: 200 });
    fireEvent.mouseUp(window);

    await waitFor(() => expect(onVerified).toHaveBeenCalledWith("t-1"));
    expect(verifyCaptcha).toHaveBeenCalledTimes(1);
    expect(await screen.findByText("验证通过")).toBeInTheDocument();
  });

  it("位置不对时给出后端的原因，并且自动换一张题", async () => {
    fetchCaptcha.mockResolvedValue(challenge());
    verifyCaptcha.mockRejectedValue(new Error("拼图位置不对，请再试一次"));
    const onVerified = vi.fn();
    render(<SliderCaptcha onVerified={onVerified} />);

    const handle = await screen.findByTestId("captcha-handle");
    fireEvent.mouseDown(handle);
    fireEvent.mouseMove(window, { clientX: 10 });
    fireEvent.mouseUp(window);

    expect(await screen.findByText("拼图位置不对，请再试一次")).toBeInTheDocument();
    expect(onVerified).toHaveBeenCalledWith(null);
    // 失败后重新拉题（换一张）
    await waitFor(() => expect(fetchCaptcha.mock.calls.length).toBeGreaterThan(1), { timeout: 3000 });
  });

  it("点「换一张」会重新出题", async () => {
    fetchCaptcha.mockResolvedValue(challenge());
    render(<SliderCaptcha onVerified={vi.fn()} />);
    await screen.findByTestId("captcha-panel");
    fireEvent.click(screen.getByTitle("换一张"));
    await waitFor(() => expect(fetchCaptcha).toHaveBeenCalledTimes(2));
  });
});
