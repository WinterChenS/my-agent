// 本地策略：URL 只允许公网 HTTPS；拒绝 localhost / 私网 / 浏览器内部页面。
// 与后端 ReachUrlPolicy 保持同一套思路（后端是权威，扩展这里再做一次，防止后端被绕过）。
const PRIVATE_HOST = /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.|\[?::1\]?$)/i;

export function checkUrl(raw) {
  let url;
  try {
    url = new URL(raw);
  } catch {
    return { ok: false, reason: '地址不是合法 URL' };
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    return { ok: false, reason: '只能操作 http/https 网页（浏览器内部页面、本地文件不支持）' };
  }
  if (url.username || url.password) {
    return { ok: false, reason: '地址里不能带账号密码' };
  }
  if (PRIVATE_HOST.test(url.hostname) || !url.hostname.includes('.')) {
    return { ok: false, reason: '不能操作内网、本机或非公网地址' };
  }
  if (url.port && !['80', '443', ''].includes(url.port)) {
    return { ok: false, reason: '只支持标准端口（80/443）' };
  }
  return { ok: true, origin: url.origin };
}

export function sameOrigin(a, b) {
  if (!a || !b) return true; // 空表示还没授权具体站点，不拦截
  try {
    return new URL(a).origin === new URL(b).origin;
  } catch {
    return false;
  }
}
