import { HttpAgent } from "@ag-ui/client";
import { useAuthStore } from "@/stores/auth";

/**
 * 创建聊天用 HttpAgent。
 *
 * <p>用自定义 fetch 注入 {@code Authorization: Bearer}——SSE 无法通过 EventSource 传 header，
 * 这也是技术方案第 8 节选 fetch 方案的原因。
 */
export function createChatAgent(): HttpAgent {
  const authFetch = (url: string, init?: RequestInit): Promise<Response> => {
    const token = useAuthStore.getState().token;
    const headers = new Headers(init?.headers);
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }
    return fetch(url, { ...init, headers });
  };

  return new HttpAgent({
    url: "/agui/run",
    fetch: authFetch as never,
  });
}
