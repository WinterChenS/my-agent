import { apiFetch } from "./client";
import { useAuthStore } from "@/stores/auth";

/** 浏览器会话状态。 */
export interface BrowserStatus {
  enabled: boolean;
  active: boolean;
  url?: string;
  title?: string;
  width?: number;
  height?: number;
  userControlled?: boolean;
  status?: string;
  allowUserInput?: boolean;
}

export function fetchBrowserStatus() {
  return apiFetch<BrowserStatus>("/api/browser/status");
}

/** 用户操作回传（点击/滚动/输入/按键）。坐标为**视口像素**（不是屏幕像素）。 */
export type BrowserInput =
  | { type: "click"; x: number; y: number }
  | { type: "scroll"; x: number; y: number; deltaY: number }
  | { type: "text"; text: string }
  | { type: "key"; key: string };

export function sendBrowserInput(body: BrowserInput) {
  return apiFetch<void>("/api/browser/input", { method: "POST", body: JSON.stringify(body) });
}

export function navigateBrowser(url: string) {
  return apiFetch<void>("/api/browser/navigate", { method: "POST", body: JSON.stringify({ url }) });
}

/** 改真实视口尺寸（拖拽面板 = 改页面宽度，页面会 reflow，画面 1:1 铺满无黑边）。 */
export function setBrowserViewport(width: number, height: number) {
  return apiFetch<void>("/api/browser/viewport", {
    method: "POST",
    body: JSON.stringify({ width, height }),
  });
}

export function takeoverBrowser(on: boolean) {
  return apiFetch<void>("/api/browser/takeover", {
    method: "POST",
    body: JSON.stringify({ on }),
  });
}

export function closeBrowser() {
  return apiFetch<void>("/api/browser/close", { method: "POST" });
}

export interface BrowserFrame {
  image: string;
  width: number;
  height: number;
}

/**
 * 订阅实时画面（SSE）。
 *
 * <p>为什么不用 `EventSource`：它**不能带 Authorization 头**，而我们的接口都要 Bearer。
 * 所以用 fetch + ReadableStream 自己解析 SSE 帧。
 *
 * @returns 取消订阅函数
 */
export function openBrowserFrames(
  onFrame: (frame: BrowserFrame) => void,
  onError?: (message: string) => void,
): () => void {
  const controller = new AbortController();
  // EventSource 不能带 Authorization，所以走 fetch + stream；token 与 apiFetch 同源
  const token = useAuthStore.getState().token;

  void (async () => {
    try {
      const response = await fetch("/api/browser/frames", {
        headers: token ? { Authorization: `Bearer ${token}`, Accept: "text/event-stream" } : {},
        signal: controller.signal,
      });
      if (!response.ok || !response.body) {
        onError?.(`画面连接失败（HTTP ${response.status}）`);
        return;
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          break;
        }
        buffer += decoder.decode(value, { stream: true });
        // SSE 以空行分帧
        let index = buffer.indexOf("\n\n");
        while (index >= 0) {
          const chunk = buffer.slice(0, index);
          buffer = buffer.slice(index + 2);
          const dataLines = chunk
            .split("\n")
            .filter((line) => line.startsWith("data:"))
            .map((line) => line.slice(5).trim());
          if (dataLines.length > 0) {
            try {
              const frame = JSON.parse(dataLines.join("\n")) as BrowserFrame;
              if (frame?.image) {
                onFrame(frame);
              }
            } catch {
              // 帧解析失败就跳过，别中断整条流
            }
          }
          index = buffer.indexOf("\n\n");
        }
      }
    } catch (err) {
      if (!controller.signal.aborted) {
        onError?.(err instanceof Error ? err.message : "画面连接中断");
      }
    }
  })();

  return () => controller.abort();
}
