import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BrowserPreview } from "./BrowserPreview";

const fetchBrowserStatus = vi.fn();
const sendBrowserInput = vi.fn();
const takeoverBrowser = vi.fn();
const closeBrowser = vi.fn();
const navigateBrowser = vi.fn();
let frameCallback: ((frame: { image: string; width: number; height: number }) => void) | null = null;
const stopFrames = vi.fn();

vi.mock("@/api/browser", () => ({
  fetchBrowserStatus: () => fetchBrowserStatus(),
  sendBrowserInput: (body: unknown) => sendBrowserInput(body),
  takeoverBrowser: (on: boolean) => takeoverBrowser(on),
  closeBrowser: () => closeBrowser(),
  navigateBrowser: (url: string) => navigateBrowser(url),
  setBrowserViewport: () => Promise.resolve(),
  openBrowserFrames: (onFrame: (frame: { image: string; width: number; height: number }) => void) => {
    frameCallback = onFrame;
    return stopFrames;
  },
}));

function activeStatus(overrides: Record<string, unknown> = {}) {
  return {
    enabled: true,
    active: true,
    url: "https://example.com/",
    title: "Example Domain",
    width: 1280,
    height: 800,
    allowUserInput: true,
    userControlled: false,
    status: "ready",
    ...overrides,
  };
}

afterEach(cleanup);

describe("BrowserPreview 浏览器视图", () => {
  beforeEach(() => {
    fetchBrowserStatus.mockReset();
    sendBrowserInput.mockReset();
    takeoverBrowser.mockReset().mockResolvedValue(undefined);
    navigateBrowser.mockReset().mockResolvedValue(undefined);
    closeBrowser.mockReset().mockResolvedValue(undefined);
    frameCallback = null;
    // 面板显示尺寸固定 640x400：现在 canvas 是 1:1 铺满（内部像素 = 显示尺寸，不缩放）
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({
      left: 0, top: 0, width: 640, height: 400, right: 640, bottom: 400, x: 0, y: 0,
      toJSON: () => ({}),
    } as DOMRect);
  });

  it("没有活跃浏览器时给引导提示（不报错、不白屏）", async () => {
    fetchBrowserStatus.mockResolvedValue({ enabled: true, active: false });
    render(<BrowserPreview onClose={vi.fn()} />);
    expect(await screen.findByText(/还没有打开浏览器/)).toBeInTheDocument();
    expect(screen.queryByTestId("browser-canvas")).toBeNull();
  });

  it("有会话时渲染 canvas，并在收到帧后开始显示画面", async () => {
    fetchBrowserStatus.mockResolvedValue(activeStatus());
    render(<BrowserPreview onClose={vi.fn()} />);

    const canvas = (await screen.findByTestId("browser-canvas")) as HTMLCanvasElement;
    expect(canvas).toBeTruthy();
    // 订阅后推一帧（1x1 的 base64 JPEG 占位即可，测试只关心流程）
    await waitFor(() => expect(frameCallback).not.toBeNull());
    // canvas 内部像素 = 面板显示尺寸（1:1 铺满，不留黑边）
    await waitFor(() => expect(canvas.width).toBe(640));
    expect(canvas.height).toBe(400);
    frameCallback?.({ image: "/9j/4AAQSkZJRg==", width: 1280, height: 800 });
  });

  it("默认就能点画面：第一次点击会自动接管，并把点击发给服务端", async () => {
    fetchBrowserStatus.mockResolvedValue(activeStatus());
    render(<BrowserPreview onClose={vi.fn()} />);
    const canvas = await screen.findByTestId("browser-canvas");

    // 不再需要先点「我要操作」——用户反馈"我来操作也没反应"就是那段门槛造成的
    expect(canvas.className).not.toContain("pointer-events-none");
    fireEvent.click(canvas, { clientX: 100, clientY: 50 });
    await waitFor(() => expect(takeoverBrowser).toHaveBeenCalledWith(true));
    await waitFor(() => expect(sendBrowserInput).toHaveBeenCalled());
  });

  it("接管后点击画面：把坐标按 canvas 比例换算成视口坐标再回传", async () => {
    fetchBrowserStatus.mockResolvedValue(activeStatus());
    render(<BrowserPreview onClose={vi.fn()} />);
    const canvas = await screen.findByTestId("browser-canvas");
    fireEvent.click(screen.getByRole("button", { name: /我要操作/ }));

    await waitFor(() => expect(takeoverBrowser).toHaveBeenCalledWith(true));
    // canvas 内部像素 = 显示尺寸（1:1），所以点 (320,200) 就是视口坐标 (320,200)
    fireEvent.click(canvas, { clientX: 320, clientY: 200 });
    await waitFor(() =>
      expect(sendBrowserInput).toHaveBeenCalledWith({ type: "click", x: 320, y: 200 }),
    );
  });

  it("点关闭会释放浏览器并关闭面板", async () => {
    fetchBrowserStatus.mockResolvedValue(activeStatus());
    const onClose = vi.fn();
    render(<BrowserPreview onClose={onClose} />);
    await screen.findByTestId("browser-canvas");

    fireEvent.click(screen.getByTitle("关闭浏览器"));
    await waitFor(() => expect(closeBrowser).toHaveBeenCalled());
    expect(onClose).toHaveBeenCalled();
  });
});
