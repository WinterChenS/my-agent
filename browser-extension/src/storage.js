import { DEFAULT_PLATFORM_URL } from './config.js';

// 统一存储：auth 用 session 存 access token（关浏览器即失效），refresh 用 local。
export const KEYS = {
  platform: 'myagent.platformUrl',
  installId: 'myagent.installId',
  device: 'myagent.device',
  refresh: 'myagent.refreshToken',
  access: 'myagent.accessToken',
  grant: 'myagent.tabGrant',
  control: 'myagent.controlState',
  pending: 'myagent.pendingGrant',
  // v2：默认开启自动接管（旧键名保留不动，等于给老用户重置一次默认值）
  autoGrant: 'myagent.autoGrant.v2',
};

export async function get(key, fallback = null) {
  const obj = await chrome.storage.local.get(key);
  return obj[key] === undefined ? fallback : obj[key];
}

export async function set(key, value) {
  await chrome.storage.local.set({ [key]: value });
}

export async function remove(key) {
  await chrome.storage.local.remove(key);
}

export async function ensureInstallId() {
  let id = await get(KEYS.installId);
  if (!id) {
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    id = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
    await set(KEYS.installId, id);
  }
  return id;
}

export async function platformUrl() {
  const value = await get(KEYS.platform);
  const fallback = DEFAULT_PLATFORM_URL;
  return (value || fallback || '').replace(/\/+$/, '');
}

export async function auth() {
  return {
    accessToken: await get(KEYS.access),
    refreshToken: await get(KEYS.refresh),
    device: await get(KEYS.device),
  };
}
