/**
 * 网页 ↔ 浏览器扩展（`externally_connectable`）。
 *
 * <p>能做什么：检测扩展是否安装、发起配对（拿 userCode 让网页自己 approve）、读写「自动接管」开关。
 * 不能做什么：**执行任何浏览器动作** —— 执行通道只有一条（后端 WSS + 设备令牌），
 * 否则任何能打开平台的页面都能驱动用户的浏览器。
 *
 * <p>注意：网页里没有 `chrome.runtime` 时会直接返回 null（Firefox/Safari/未安装扩展），
 * 调用方必须当"检测不到"处理，不能当成"没装"下结论。
 */

export interface ExtensionPing {
  ok: boolean;
  version: string;
  extensionId: string;
  installId?: string;
  paired: boolean;
  connected: boolean;
  autoGrant: boolean;
}

type ChromeRuntime = {
  sendMessage: (
    extensionId: string,
    message: unknown,
    callback: (response?: unknown) => void,
  ) => void;
  lastError?: { message?: string };
};

function runtime(): ChromeRuntime | null {
  const chrome = (window as unknown as { chrome?: { runtime?: ChromeRuntime } }).chrome;
  return chrome?.runtime?.sendMessage ? chrome.runtime : null;
}

/** 与扩展通信：未安装/未授权外部消息时安全返回 null（读掉 lastError 避免控制台报错）。 */
function sendToExtension(extensionId: string, message: unknown, timeoutMs = 1500): Promise<unknown | null> {
  const rt = runtime();
  if (!rt || !extensionId) {
    return Promise.resolve(null);
  }
  return new Promise((resolve) => {
    let settled = false;
    const finish = (value: unknown | null) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timer);
      resolve(value);
    };
    const timer = window.setTimeout(() => finish(null), timeoutMs);
    try {
      rt.sendMessage(extensionId, message, (response?: unknown) => {
        // 没装扩展时会设置 lastError（"Could not establish connection"），必须读一下
        void rt.lastError?.message;
        finish(response ?? null);
      });
    } catch {
      finish(null);
    }
  });
}

export async function pingExtension(extensionId: string): Promise<ExtensionPing | null> {
  const response = (await sendToExtension(extensionId, { type: 'bridge.ping' })) as
    | (ExtensionPing & { ok?: boolean })
    | null;
  if (!response || !response.ok) {
    return null;
  }
  return response as ExtensionPing;
}

export async function requestPairing(
  extensionId: string,
): Promise<{ ok: boolean; userCode?: string; message?: string }> {
  const response = (await sendToExtension(extensionId, { type: 'bridge.pair' }, 4000)) as
    | { ok: boolean; userCode?: string; message?: string }
    | null;
  return response ?? { ok: false, message: "浏览器扩展没有响应（可能未安装或已被停用）" };
}

export async function setAutoGrant(extensionId: string, enabled: boolean): Promise<boolean> {
  const response = (await sendToExtension(extensionId, { type: "bridge.setAutoGrant", enabled })) as
    | { ok?: boolean; autoGrant?: boolean }
    | null;
  return Boolean(response?.ok && response.autoGrant);
}

/** 浏览器类型：只用于给对应的商店/内测包链接，不能当安全判断。 */
export function detectBrowser(): "edge" | "chrome" | "other" {
  const ua = navigator.userAgent;
  if (/Edg\//.test(ua)) return "edge";
  if (/Chrome\//.test(ua)) return "chrome";
  return "other";
}

/** 侧边栏只能由用户点扩展图标打开（chrome.sidePanel.open 需要扩展内的用户手势），这里给出操作提示。 */
export const SIDE_PANEL_HINT = "点浏览器工具栏上的扩展图标 → 侧边栏里点「显示画面」，就能一边看页面一边操作。";
