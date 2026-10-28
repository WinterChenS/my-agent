/**
 * 生成一个唯一 id。
 *
 * 为什么不能直接用 `crypto.randomUUID()`（真实故障）：它只在**安全上下文**里可用——
 * HTTPS 或 localhost。部署到内网用 `http://192.168.3.10:18080` 打开时，这个函数
 * **根本不存在**，一点发送就抛 `TypeError: crypto.randomUUID is not a function`，
 * 表现为"点了发送毫无反应、停止按钮也不出现"（请求压根没发出去）。
 *
 * 降级顺序：
 *  1. `crypto.randomUUID()`（安全上下文，最快最标准）
 *  2. `crypto.getRandomValues()`（非安全上下文里也有，但兼容老浏览器需要兜底）
 *  3. `Math.random()`（最后兜底，够客户端本地去重/列表 key 用）
 */
export function newId(): string {
  const cryptoObj = globalThis.crypto;

  if (cryptoObj && typeof cryptoObj.randomUUID === "function") {
    try {
      return cryptoObj.randomUUID();
    } catch {
      // 某些环境（旧 Safari / 被策略禁用）会抛，继续走降级
    }
  }

  const bytes = new Uint8Array(16);
  if (cryptoObj && typeof cryptoObj.getRandomValues === "function") {
    cryptoObj.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i += 1) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  // RFC 4122 v4 格式
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
