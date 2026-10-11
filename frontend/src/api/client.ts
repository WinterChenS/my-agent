import { useAuthStore } from "@/stores/auth";
import type { Result } from "./types";

/** 业务错误：code 为后端返回的业务码，401 会顺带清空登录态。 */
export class ApiError extends Error {
  readonly code: number;

  constructor(code: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.code = code;
  }
}

/**
 * 统一请求封装：自动带 Bearer、解析 Result、把非 0 业务码抛成 ApiError。
 *
 * <p>SSE（/agui/run）不走这里，由 @ag-ui/client 的 HttpAgent 负责。
 */
export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = useAuthStore.getState().token;
  const headers = new Headers(init.headers);
  // FormData 必须让浏览器自己带 boundary，不能写成 application/json
  const isFormData = typeof FormData !== "undefined" && init.body instanceof FormData;
  if (init.body && !isFormData && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  let response: Response;
  try {
    response = await fetch(path, { ...init, headers });
  } catch {
    throw new ApiError(-1, "网络不可用，请检查后端服务是否已启动");
  }

  const text = await response.text();
  let payload: Result<T> | null = null;
  if (text) {
    try {
      payload = JSON.parse(text) as Result<T>;
    } catch {
      payload = null;
    }
  }

  if (!response.ok || (payload && payload.code !== 0)) {
    const code = payload?.code ?? response.status;
    const message = payload?.msg ?? `请求失败（HTTP ${response.status}）`;
    if (response.status === 401 || code === 401) {
      useAuthStore.getState().clear();
    }
    throw new ApiError(code, message);
  }
  return (payload as Result<T>).data;
}
