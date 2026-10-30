import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SliderCaptcha } from "./SliderCaptcha";

const fetchCaptcha = vi.fn();
const verifyCaptcha = vi.fn();
vi.mock("@/api/auth", () => ({
  fetchCaptcha: (sessionKey: string, mode: string) => fetchCaptcha(sessionKey, mode),
  verifyCaptcha: (body: unknown) => verifyCaptcha(body),
}));
vi.mock("@/lib/captchaSession", () => ({ captchaSessionKey: () => "test-session-key" }));

function sliderChallenge(overrides: Record<string, unknown> = {}) {
  return {
    required: true,
    mode: "SLIDER",
    id: "c1",
    background: "data:image/png;base64,AAA",
    piece: "data:image/png;base64,BBB",
    pieceY: 40,
    pieceSize: 72,
    piecePad: 14,
    width: 320,
    height: 160,
    ttlSeconds: 90,
    question: null,
    ...overrides,
  };
}

function textChallenge() {
  return sliderChallenge({
    mode: "TEXT",
    id: "t1",
    background: null,
    piece: null,
    pieceY: null,
    question: "7 + 5 = ?",
  });
}

describe("SliderCaptcha", () => {
  beforeEach(() => {
    fetchCaptcha.mockReset();
    verifyCaptcha.mockReset();
  });

  it("服务端关掉验证时整块不渲染，并把 null 交给登录表单", async () => {
    fetchCaptcha.mockResolvedValue(sliderChallenge({ required: false }));
    const onVerified = vi.fn();
    const { container } = render(<SliderCaptcha onVerified={onVerified} />);
    await waitFor(() => expect(onVerified).toHaveBeenCalledWith(null));
    expect(container).toBeEmptyDOMElement();
  });

  it("出题时带上会话标识（票据与会话绑定）", async () => {
    fetchCaptcha.mockResolvedValue(sliderChallenge());
    render(<SliderCaptcha onVerified={vi.fn()} />);
    await waitFor(() => expect(fetchCaptcha).toHaveBeenCalledWith("test-session-key", "slider"));
  });

  it("拼图平时不展示（不占位），按下滑块才浮出来，校验完自动收起", async () => {
    fetchCaptcha.mockResolvedValue(sliderChallenge());
    verifyCaptcha.mockResolvedValue({ ticket: "t-0" });
    render(<SliderCaptcha onVerified={vi.fn()} />);

    const handle = await screen.findByTestId("captcha-handle");
    const panel = screen.getByTestId("captcha-panel");
    expect(panel.className).toContain("invisible");
    expect(panel.className).toContain("absolute");
    expect(panel.getAttribute("data-open")).toBe("false");

    fireEvent.mouseDown(handle);
    expect(screen.getByTestId("captcha-panel").getAttribute("data-open")).toBe("true");

    fireEvent.mouseMove(window, { clientX: 100 });
    fireEvent.mouseUp(window);
    await waitFor(() => expect(verifyCaptcha).toHaveBeenCalledTimes(1));
    await waitFor(
      () => expect(screen.getByTestId("captcha-panel").getAttribute("data-open")).toBe("false"),
      { timeout: 3000 },
    );
  });

  it("通过后拿到的 ticket 来自服务端；请求里带 sessionKey / x / 轨迹", async () => {
    fetchCaptcha.mockResolvedValue(sliderChallenge());
    verifyCaptcha.mockResolvedValue({ ticket: "t-1" });
    const onVerified = vi.fn();
    render(<SliderCaptcha onVerified={onVerified} />);

    const handle = await screen.findByTestId("captcha-handle");
    fireEvent.mouseDown(handle);
    fireEvent.mouseMove(window, { clientX: 200 });
    fireEvent.mouseMove(window, { clientX: 210 });
    fireEvent.mouseUp(window);

    await waitFor(() => expect(onVerified).toHaveBeenCalledWith("t-1"));
    const body = verifyCaptcha.mock.calls[0][0] as {
      id: string;
      sessionKey: string;
      x: number;
      track: number[][];
    };
    expect(body.id).toBe("c1");
    expect(body.sessionKey).toBe("test-session-key");
    expect(typeof body.x).toBe("number");
    // 轨迹：首点时间为 0，且时间单调不减
    expect(body.track.length).toBeGreaterThanOrEqual(2);
    expect(body.track[0][1]).toBe(0);
    for (let i = 1; i < body.track.length; i += 1) {
      expect(body.track[i][1]).toBeGreaterThanOrEqual(body.track[i - 1][1]);
    }
  });

  it("服务端说失败就是失败：前端没有本地判定，回调只拿到 null", async () => {
    fetchCaptcha.mockResolvedValue(sliderChallenge());
    verifyCaptcha.mockRejectedValue(new Error("拼图位置不对，请再试一次"));
    const onVerified = vi.fn();
    render(<SliderCaptcha onVerified={onVerified} />);

    // 拖到"看起来到位"的位置（滑轨末端）
    const handle = await screen.findByTestId("captcha-handle");
    fireEvent.mouseDown(handle);
    fireEvent.mouseMove(window, { clientX: 320 });
    fireEvent.mouseUp(window);

    expect(await screen.findByText("拼图位置不对，请再试一次")).toBeInTheDocument();
    expect(onVerified).toHaveBeenCalledWith(null);
    expect(onVerified).not.toHaveBeenCalledWith(expect.any(String));
  });

  it("失败后自动换一张题", async () => {
    fetchCaptcha.mockResolvedValue(sliderChallenge());
    verifyCaptcha.mockRejectedValue(new Error("验证失败"));
    render(<SliderCaptcha onVerified={vi.fn()} />);

    const handle = await screen.findByTestId("captcha-handle");
    fireEvent.mouseDown(handle);
    fireEvent.mouseMove(window, { clientX: 10 });
    fireEvent.mouseUp(window);

    await waitFor(() => expect(fetchCaptcha.mock.calls.length).toBeGreaterThan(1), { timeout: 3000 });
  });

  it("点「换一张」会重新出题", async () => {
    fetchCaptcha.mockResolvedValue(sliderChallenge());
    render(<SliderCaptcha onVerified={vi.fn()} />);
    await screen.findByTestId("captcha-panel");
    const before = fetchCaptcha.mock.calls.length;
    fireEvent.click(screen.getByTitle("换一张"));
    await waitFor(() => expect(fetchCaptcha.mock.calls.length).toBeGreaterThan(before));
  });

  it("无障碍替代方案：可以切换成算术题并提交文字答案", async () => {
    fetchCaptcha.mockResolvedValueOnce(sliderChallenge()).mockResolvedValueOnce(textChallenge());
    verifyCaptcha.mockResolvedValue({ ticket: "t-text" });
    const onVerified = vi.fn();
    render(<SliderCaptcha onVerified={onVerified} />);

    fireEvent.click(await screen.findByTestId("captcha-switch-mode"));
    expect(await screen.findByTestId("captcha-question")).toHaveTextContent("7 + 5 = ?");

    fireEvent.change(screen.getByTestId("captcha-answer"), { target: { value: "12" } });
    fireEvent.click(screen.getByRole("button", { name: "验证" }));

    await waitFor(() => expect(onVerified).toHaveBeenCalledWith("t-text"));
    const body = verifyCaptcha.mock.calls[0][0] as { id: string; answer: string; x?: number };
    expect(body.id).toBe("t1");
    expect(body.answer).toBe("12");
    expect(body.x).toBeUndefined();
  });
});
