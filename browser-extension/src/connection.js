// WSS 长连接：首帧 auth、心跳、指数退避重连、结果回传。
import { EXTENSION_VERSION, PROTOCOL_VERSION, newId } from './protocol.js';
import { KEYS, auth, get, platformUrl, set } from './storage.js';
import { refreshTokens } from './pairing.js';

const HEARTBEAT_MS = 20000;

let socket = null;
let attempt = 0;
let heartbeatTimer = null;
let onCommand = null;
let onStatus = null;
let connectionEpoch = 0;

export function status() {
  return {
    connected: !!(socket && socket.readyState === WebSocket.OPEN),
    connectionEpoch,
    platform: null,
  };
}

export function setHandlers(handlers) {
  onCommand = handlers.onCommand;
  onStatus = handlers.onStatus;
}

export async function connect() {
  const base = await platformUrl();
  if (!base) {
    return;
  }
  const { accessToken } = await auth();
  if (!accessToken) {
    return;
  }
  const url = base.replace(/^http/, 'ws') + '/api/browser/bridge/ws';
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return;
  }
  socket = new WebSocket(url);

  socket.onopen = async () => {
    attempt = 0;
    const device = await get(KEYS.device, {});
    send({
      type: 'auth',
      token: (await auth()).accessToken,
      protocolVersion: PROTOCOL_VERSION,
      extensionVersion: EXTENSION_VERSION,
      installId: await chrome.storage.local.get('myagent.installId').then((o) => o['myagent.installId']),
      displayName: device && device.id ? '本机浏览器' : '本机浏览器',
      browser: navigator.userAgent.includes('Edg/') ? 'edge' : 'chrome',
    });
  };

  socket.onmessage = async (event) => {
    let frame;
    try {
      frame = JSON.parse(event.data);
    } catch {
      return;
    }
    switch (frame.type) {
      case 'auth.ok':
        connectionEpoch = frame.connectionEpoch || 0;
        startHeartbeat();
        notify({ connected: true, deviceId: frame.deviceId, connectionEpoch });
        break;
      case 'auth.error':
        notify({ connected: false, error: frame.message || '鉴权失败' });
        if (frame.errorCode === 'CREDENTIAL_STALE' || frame.errorCode === 'DEVICE_REVOKED') {
          try {
            await refreshTokens();
            await connect();
          } catch {
            /* 需要重新配对 */
          }
        }
        break;
      case 'command':
        if (onCommand) {
          onCommand(frame);
        }
        break;
      case 'session.update':
        await set(KEYS.control, {
          controlSessionId: frame.controlSessionId,
          state: frame.state,
          leaseVersion: frame.leaseVersion,
          pageRevision: frame.pageRevision,
        });
        notify({ session: frame });
        break;
      case 'revoked':
        notify({ connected: false, error: frame.message || '设备已被撤销' });
        socket.close();
        break;
      default:
        break;
    }
  };

  socket.onclose = () => {
    stopHeartbeat();
    notify({ connected: false });
    scheduleReconnect();
  };
  socket.onerror = () => {
    /* onclose 会跟着来 */
  };
}

function scheduleReconnect() {
  attempt += 1;
  const delay = Math.min(30000, 1000 * Math.pow(2, Math.min(attempt, 5))) + Math.random() * 500;
  setTimeout(() => connect().catch(() => {}), delay);
}

function startHeartbeat() {
  stopHeartbeat();
  heartbeatTimer = setInterval(() => send({ type: 'heartbeat', ts: Date.now() }), HEARTBEAT_MS);
}

function stopHeartbeat() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer);
    heartbeatTimer = null;
  }
}

export function send(frame) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(frame));
    return true;
  }
  return false;
}

export function sendResult(requestId, result) {
  send({
    type: 'result',
    requestId,
    status: result.ok ? 'OK' : 'ERROR',
    errorCode: result.errorCode || null,
    message: result.message || null,
    pageRevision: result.pageRevision || 0,
    controlState: result.controlState || null,
    durationMs: result.durationMs || 0,
    data: result.data || {},
  });
}

export function sendEvent(controlSessionId, event, message, pageRevision) {
  send({ type: 'event', controlSessionId, event, message, pageRevision });
}

export function sendTabState(controlSessionId, tab) {
  send({
    type: 'tab.state',
    controlSessionId,
    tabHandle: String(tab.tabId),
    origin: tab.origin,
    title: tab.title,
    url: tab.url,
    pageRevision: tab.pageRevision || 0,
    controlState: tab.controlState || null,
  });
}

function notify(payload) {
  if (onStatus) {
    onStatus(payload);
  }
  chrome.runtime.sendMessage({ type: 'bridge-status', payload }).catch(() => {});
}
