import { newId } from "./id";

/**
 * 验证码会话标识（sessionKey）。
 *
 * <p>作用：把「题目」和「票据」绑到一个会话上 —— 换会话/搬题/偷 ticket 都用不了。
 * 只用 `sessionStorage`（关掉标签页就换一个），不做浏览器指纹（内网 http 下
 * `crypto.subtle` 不存在，而且指纹本身也会误伤正常用户）。
 */
const KEY = "myagent-captcha-session";

let memoryFallback: string | null = null;

export function captchaSessionKey(): string {
  try {
    const existing = window.sessionStorage.getItem(KEY);
    if (existing && existing.length >= 8) {
      return existing;
    }
    const created = newId();
    window.sessionStorage.setItem(KEY, created);
    return created;
  } catch {
    // 隐私模式 / 存储被禁用：退化成内存态（本页内仍然一致）
    if (!memoryFallback) {
      memoryFallback = newId();
    }
    return memoryFallback;
  }
}
