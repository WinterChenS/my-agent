import { HttpAgent } from "@ag-ui/client";
import { useAuthStore } from "@/stores/auth";

/**
 * 创建聊天用 HttpAgent。
 *
 * <p>用自定义 fetch 注入 {@code Authorization: Bearer}——SSE 无法通过 EventSource 传 header，
 * 这也是技术方案第 8 节选 fetch 方案的原因。
 */
export function createChatAgent(): HttpAgent {
  // Phase 12 / D25：把当前在途请求的 AbortController 暴露出来，供「停止」按钮中断 SSE
  let controller = new AbortController();
  currentAbort = () => controller.abort();

  const authFetch = (url: string, init?: RequestInit): Promise<Response> => {
    const token = useAuthStore.getState().token;
    const headers = new Headers(init?.headers);
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }
    // 每次请求用新的 signal（上一个可能已经 abort 过）
    if (controller.signal.aborted) {
      controller = new AbortController();
      currentAbort = () => controller.abort();
    }
    return fetch(url, { ...init, headers, signal: controller.signal });
  };

  return new HttpAgent({
    url: "/agui/run",
    fetch: authFetch as never,
  });
}

/** 中断当前在途的 AG-UI 请求（没有在途请求时是空操作）。 */
let currentAbort: (() => void) | null = null;

export function abortCurrentRun() {
  currentAbort?.();
}
