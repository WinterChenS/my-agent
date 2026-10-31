// 协议常量与错误码：必须与后端 BrowserBridgeHub / 方案第 9 节一致。
export const PROTOCOL_VERSION = 1;
export const EXTENSION_VERSION = '0.1.0';
export const ALLOWED_ACTIONS = new Set([
  'get_snapshot',
  'click',
  'type_text',
  'press_key',
  'scroll',
  'navigate',
  'capture_screenshot',
  'wait_for',
  'focus_tab',
  'request_tab_grant',
]);
export const WRITE_ACTIONS = new Set(['click', 'type_text', 'press_key', 'navigate']);

export const ERR = {
  EXTENSION_REQUIRED: 'EXTENSION_REQUIRED',
  DEVICE_OFFLINE: 'DEVICE_OFFLINE',
  VERSION_UNSUPPORTED: 'VERSION_UNSUPPORTED',
  TAB_NOT_AUTHORIZED: 'TAB_NOT_AUTHORIZED',
  ORIGIN_CHANGED: 'ORIGIN_CHANGED',
  STALE_PAGE: 'STALE_PAGE',
  ELEMENT_NOT_FOUND: 'ELEMENT_NOT_FOUND',
  HUMAN_CONTROL: 'HUMAN_CONTROL',
  APPROVAL_REQUIRED: 'APPROVAL_REQUIRED',
  DEBUGGER_DETACHED: 'DEBUGGER_DETACHED',
  COMMAND_EXPIRED: 'COMMAND_EXPIRED',
  OUTCOME_UNKNOWN: 'OUTCOME_UNKNOWN',
  LOGIN_REQUIRED: 'LOGIN_REQUIRED',
  POLICY_BLOCKED: 'POLICY_BLOCKED',
};

export function newId(prefix) {
  const bytes = new Uint8Array(12);
  crypto.getRandomValues(bytes);
  return prefix + Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

export function base64url(bytes) {
  let s = '';
  bytes.forEach((b) => { s += String.fromCharCode(b); });
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export async function sha256Base64Url(text) {
  const data = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return base64url(new Uint8Array(digest));
}
