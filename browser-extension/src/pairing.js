import { KEYS, ensureInstallId, get, platformUrl, remove, set } from './storage.js';
import { EXTENSION_VERSION, PROTOCOL_VERSION, base64url, newId, sha256Base64Url } from './protocol.js';

// 设备配对（device code + PKCE）。平台地址由用户显式确认，不从网页消息里读取。
/**
 * 只创建配对，不打开平台标签页。
 *
 * <p>给「网页一键连接」用：网页（已登录）自己会调 approve，扩展只需拿到 userCode 并等凭证。
 */
export async function createPairing() {
  const base = await platformUrl();
  if (!base) {
    throw new Error('还没有设置平台地址');
  }
  const installId = await ensureInstallId();
  const verifierBytes = new Uint8Array(32);
  crypto.getRandomValues(verifierBytes);
  const verifier = base64url(verifierBytes);
  const challenge = await sha256Base64Url(verifier);

  const res = await fetch(`${base}/api/browser/extension/pairings`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      installId,
      pkceChallenge: challenge,
      displayName: browserName(),
      browser: browserName().toLowerCase(),
    }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok || body.code !== 0) {
    throw new Error(body.msg || `创建配对失败（HTTP ${res.status}）`);
  }
  const data = body.data;
  await set('myagent.pairing', { id: data.id, deviceCode: data.deviceCode, verifier, userCode: data.userCode });
  return data;
}

/** 打开平台配对页（用户手动配对时用）。 */
export async function startPairing() {
  const base = await platformUrl();
  const data = await createPairing();
  const verifyUrl = `${base}${data.verificationUri}?code=${encodeURIComponent(data.userCode)}`;
  await chrome.tabs.create({ url: verifyUrl });
  pollPairing(data.deviceCode, data.expiresIn || 300);
  return data;
}

/** 等待用户在网页批准并领取凭证（网页一键连接里由 background 启动）。 */
export async function waitForPairing(deviceCode, ttlSeconds) {
  await pollPairing(deviceCode, ttlSeconds || 300);
}

async function pollPairing(deviceCode, ttlSeconds) {
  const base = await platformUrl();
  const pairing = await get('myagent.pairing');
  const deadline = Date.now() + ttlSeconds * 1000;
  while (Date.now() < deadline) {
    await sleep(5000);
    const res = await fetch(`${base}/api/browser/extension/pairings/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        deviceCode,
        verifier: pairing.verifier,
        extensionVersion: EXTENSION_VERSION,
        protocolVersion: PROTOCOL_VERSION,
      }),
    });
    const body = await res.json().catch(() => ({}));
    if (body.code === 0) {
      await saveTokens(body.data);
      await chrome.runtime.sendMessage({ type: 'paired' }).catch(() => {});
      return body.data;
    }
    // 40008 = 还没在网页批准，继续等；其它错误直接停
    if (body.code && body.code !== 40008) {
      throw new Error(body.msg || '配对失败');
    }
  }
  throw new Error('配对超时，请重新开始');
}

export async function saveTokens(data) {
  await set(KEYS.access, data.accessToken);
  await set(KEYS.refresh, data.refreshToken);
  await set(KEYS.device, {
    id: data.deviceId,
    userId: data.userId,
    credentialVersion: data.credentialVersion,
    pairedAt: Date.now(),
  });
  await remove('myagent.pairing');
}

export async function refreshTokens() {
  const base = await platformUrl();
  const refreshToken = await get(KEYS.refresh);
  if (!refreshToken) {
    throw new Error('没有 refreshToken');
  }
  const res = await fetch(`${base}/api/browser/extension/token/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  });
  const body = await res.json().catch(() => ({}));
  if (body.code !== 0) {
    throw new Error(body.msg || '刷新设备令牌失败');
  }
  await saveTokens(body.data);
  return body.data.accessToken;
}

export async function unpair() {
  await remove(KEYS.access);
  await remove(KEYS.refresh);
  await remove(KEYS.device);
  await remove(KEYS.grant);
  await remove(KEYS.control);
}

export function browserName() {
  const ua = navigator.userAgent;
  if (/Edg\//.test(ua)) return 'Edge';
  if (/Chrome\//.test(ua)) return 'Chrome';
  return 'Browser';
}

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
