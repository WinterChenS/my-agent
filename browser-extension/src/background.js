// Service worker：连接平台、接收命令、在本机标签页执行、回传结果。
// 所有执行都要求「用户已授权的标签页 + 当前处于 AGENT_CONTROL + leaseVersion 匹配」。
import { ALLOWED_ACTIONS, ERR, PROTOCOL_VERSION, WRITE_ACTIONS } from './protocol.js';
import { KEYS, auth, get, platformUrl, remove, set } from './storage.js';
import { checkUrl, sameOrigin } from './policy.js';
import { attach, clickAt, detach, navigate, pressKey, screenshot, scroll, snippet, typeText, BridgeError } from './debugger.js';
import { enqueue, recall, remember } from './scheduler.js';
import { connect, sendEvent, sendResult, sendTabState, setHandlers, status } from './connection.js';
import { startPairing, unpair } from './pairing.js';

chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});

// ---------------- 生命周期 ----------------

chrome.runtime.onInstalled.addListener(async (details) => {
  if (details.reason === 'install') {
    await chrome.tabs.create({ url: 'onboarding.html' });
  }
  chrome.alarms.create('myagent-heartbeat', { periodInMinutes: 1 });
});

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'myagent-heartbeat') {
    connect().catch(() => {});
  }
});

chrome.runtime.onStartup.addListener(() => connect().catch(() => {}));
connect().catch(() => {});

setHandlers({
  onCommand: (command) => handleCommand(command),
  onStatus: () => {},
});

// ---------------- 状态 ----------------

async function control() {
  return (await get(KEYS.control)) || null;
}

async function grant() {
  return (await get(KEYS.grant)) || null;
}

async function bumpRevision() {
  const g = await grant();
  if (g) {
    g.pageRevision = (g.pageRevision || 0) + 1;
    await set(KEYS.grant, g);
  }
  const c = await control();
  if (c) {
    c.pageRevision = g ? g.pageRevision : (c.pageRevision || 0) + 1;
    await set(KEYS.control, c);
  }
  return g ? g.pageRevision : 0;
}

// ---------------- 命令执行 ----------------

async function handleCommand(command) {
  const startedAt = Date.now();
  const cached = recall(command.requestId);
  if (cached) {
    sendResult(command.requestId, cached.result);
    return;
  }
  const finish = async (result) => {
    const payload = {
      ...result,
      pageRevision: result.pageRevision != null ? result.pageRevision : ((await grant()) || {}).pageRevision || 0,
      durationMs: Date.now() - startedAt,
    };
    remember(command.requestId, payload);
    sendResult(command.requestId, payload);
    chrome.runtime.sendMessage({ type: 'command-done', requestId: command.requestId }).catch(() => {});
  };

  try {
    if (command.protocolVersion && Number(command.protocolVersion) > PROTOCOL_VERSION) {
      throw new BridgeError(ERR.VERSION_UNSUPPORTED, '平台协议版本高于扩展版本，请更新扩展');
    }
    if (!ALLOWED_ACTIONS.has(command.action)) {
      throw new BridgeError(ERR.POLICY_BLOCKED, '不允许的动作：' + command.action);
    }
    if (command.expiresAt && Date.parse(command.expiresAt) < Date.now()) {
      throw new BridgeError(ERR.COMMAND_EXPIRED, '命令已过期');
    }

    if (command.action === 'request_tab_grant') {
      await set(KEYS.pending, {
        controlSessionId: command.controlSessionId,
        url: (command.args || {}).url || null,
        at: Date.now(),
      });
      chrome.runtime.sendMessage({ type: 'needs-grant', url: (command.args || {}).url }).catch(() => {});
      await chrome.sidePanel.open({ windowId: (await chrome.windows.getLastFocused()).id }).catch(() => {});
      // 这是"请用户授权"的通知，不是执行结果；返回明确状态让 Agent 停下来等
      await finish({ ok: false, errorCode: 'AWAITING_TAB', message: '已请求用户在本机浏览器授权标签页' });
      return;
    }

    const state = await control();
    const g = await grant();
    if (!g) {
      throw new BridgeError(ERR.TAB_NOT_AUTHORIZED, '还没有授权的标签页，请先在本页点击扩展并授权');
    }
    if (state && state.controlSessionId === command.controlSessionId) {
      if (state.state !== 'AGENT_CONTROL') {
        throw new BridgeError(ERR.HUMAN_CONTROL, '当前是本机用户控制（Agent 已暂停）');
      }
      if (Number(command.leaseVersion) !== Number(state.leaseVersion)) {
        throw new BridgeError(ERR.COMMAND_EXPIRED, '控制权已变更，这条命令已失效');
      }
    }

    const tab = await chrome.tabs.get(g.tabId).catch(() => null);
    if (!tab) {
      throw new BridgeError(ERR.TAB_NOT_AUTHORIZED, '授权的标签页已被关闭');
    }
    const currentUrl = tab.url || '';
    if (command.expectedOrigin && !sameOrigin(command.expectedOrigin, currentUrl)) {
      throw new BridgeError(ERR.ORIGIN_CHANGED, '页面已经跳到别的站点，需要重新授权');
    }
    if (command.action !== 'navigate') {
      const policy = checkUrl(currentUrl);
      if (!policy.ok) {
        throw new BridgeError(ERR.POLICY_BLOCKED, policy.reason);
      }
    }

    const result = await enqueue(g.tabId, () => runAction(command, g));
    await finish({
      ok: true,
      controlState: 'AGENT_CONTROL',
      data: result.data || {},
      pageRevision: result.pageRevision,
    });
  } catch (e) {
    const code = e instanceof BridgeError ? e.code : ERR.OUTCOME_UNKNOWN;
    if (code === ERR.DEBUGGER_DETACHED) {
      sendEvent(command.controlSessionId, 'debugger_detached', e.message, 0);
    }
    await finish({ ok: false, errorCode: code, message: e.message || String(e) });
  }
}

async function runAction(command, g) {
  const args = command.args || {};
  const tabId = g.tabId;
  const action = command.action;

  if (action === 'navigate') {
    const target = String(args.url || '');
    const policy = checkUrl(target);
    if (!policy.ok) {
      throw new BridgeError(ERR.POLICY_BLOCKED, policy.reason);
    }
    await attach(tabId);
    const state = await navigate(tabId, target);
    g.origin = new URL(state.url).origin;
    g.url = state.url;
    g.title = state.title;
    g.pageRevision = (g.pageRevision || 0) + 1;
    await set(KEYS.grant, g);
    return {
      pageRevision: g.pageRevision,
      data: { url: state.url, title: state.title, origin: g.origin, tabHandle: String(tabId),
        text: state.text || '' },
    };
  }

  await attach(tabId);
  const tab = await chrome.tabs.get(tabId);
  g.origin = g.origin || new URL(tab.url).origin;
  g.title = tab.title;

  if (action === 'get_snapshot') {
    const snap = await snippet(tabId, 'snapshot', { maxChars: Math.min(Number(args.maxChars) || 4000, 20000) });
    g.pageRevision = (g.pageRevision || 0) + 1;
    await set(KEYS.grant, g);
    const lines = [];
    lines.push('页面：' + snap.title + '（' + snap.url + '）');
    lines.push('');
    lines.push(snap.text || '（没有可见正文）');
    lines.push('');
    lines.push('可交互元素（用 elementRef 让 browser_click / browser_type 操作）：');
    (snap.elements || []).slice(0, 80).forEach((el) => {
      lines.push('  ' + el.ref + ' <' + el.tag + (el.type ? ' type=' + el.type : '') + '> ' + (el.text || '（无文字）'));
    });
    return { pageRevision: g.pageRevision, data: { text: lines.join('\n'), origin: g.origin, title: g.title } };
  }

  if (action === 'click') {
    let point = null;
    if (args.elementRef) {
      point = await snippet(tabId, 'center', { elementRef: args.elementRef });
      if (!point) {
        throw new BridgeError(ERR.ELEMENT_NOT_FOUND, '页面上找不到 ' + args.elementRef + '（页面可能已经变了，请重新 browser_read）');
      }
    } else if (args.text) {
      point = await snippet(tabId, 'pick', { text: args.text });
      if (!point) {
        throw new BridgeError(ERR.ELEMENT_NOT_FOUND, '页面上找不到包含「' + args.text + '」的元素');
      }
    } else if (args.selector) {
      point = await snippet(tabId, 'focus', { selector: args.selector });
      if (!point) {
        throw new BridgeError(ERR.ELEMENT_NOT_FOUND, '选择器没有匹配到元素：' + args.selector);
      }
    } else if (Number.isFinite(Number(args.x)) && Number.isFinite(Number(args.y))) {
      point = { x: Number(args.x), y: Number(args.y) };
    } else {
      throw new BridgeError(ERR.POLICY_BLOCKED, 'click 需要 elementRef / text / selector / x,y 之一');
    }
    await clickAt(tabId, point.x, point.y);
    await sleep(350);
    const rev = await bumpRevision();
    return { pageRevision: rev, data: { text: '已点击 ' + (args.elementRef || args.text || args.selector || `(${point.x},${point.y})`) } };
  }

  if (action === 'type_text') {
    const text = String(args.text || '');
    if (!text) {
      throw new BridgeError(ERR.POLICY_BLOCKED, 'text 不能为空');
    }
    if (args.elementRef || args.selector) {
      const point = await snippet(tabId, 'focus', { elementRef: args.elementRef, selector: args.selector });
      if (!point) {
        throw new BridgeError(ERR.ELEMENT_NOT_FOUND, '找不到输入框：' + (args.elementRef || args.selector));
      }
      await clickAt(tabId, point.x, point.y);
      await sleep(120);
    }
    await typeText(tabId, text);
    if (args.submit) {
      await sleep(120);
      await pressKey(tabId, 'Enter');
    }
    await sleep(350);
    const rev = await bumpRevision();
    return { pageRevision: rev, data: { text: '已输入「' + text + '」' + (args.submit ? ' 并回车' : '') } };
  }

  if (action === 'press_key') {
    await pressKey(tabId, String(args.key || 'Enter'));
    await sleep(250);
    const rev = await bumpRevision();
    return { pageRevision: rev, data: { text: '已按键 ' + (args.key || 'Enter') } };
  }

  if (action === 'scroll') {
    await scroll(tabId, Number(args.deltaY) || 600);
    await sleep(250);
    const rev = await bumpRevision();
    return { pageRevision: rev, data: { text: '已滚动 ' + (Number(args.deltaY) || 600) + ' 像素' } };
  }

  if (action === 'capture_screenshot') {
    const image = await screenshot(tabId);
    const rev = await bumpRevision();
    return { pageRevision: rev, data: { image, origin: g.origin, title: g.title, text: '已截图' } };
  }

  if (action === 'wait_for') {
    const want = String(args.text || '');
    const timeout = Math.min(Number(args.timeoutMs) || 8000, 25000);
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      const state = await snippet(tabId, 'readyState');
      if (want && state.text && state.text.indexOf(want) >= 0) {
        return { pageRevision: g.pageRevision, data: { text: '页面已出现「' + want + '」' } };
      }
      await sleep(400);
    }
    throw new BridgeError(ERR.OUTCOME_UNKNOWN, '等待超时：页面没有出现「' + want + '」');
  }

  if (action === 'focus_tab') {
    await chrome.tabs.update(tabId, { active: true });
    return { pageRevision: g.pageRevision, data: { text: '已切到该标签页' } };
  }

  throw new BridgeError(ERR.POLICY_BLOCKED, '不支持的动作：' + action);
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

// ---------------- 人工接管信号 ----------------

chrome.debugger.onDetach.addListener(async (source, reason) => {
  if (!source.tabId) {
    return;
  }
  const g = await grant();
  const c = await control();
  if (g && g.tabId === source.tabId && c) {
    c.state = 'SUSPENDED';
    await set(KEYS.control, c);
    sendEvent(c.controlSessionId, 'debugger_detached', '调试会话已结束（' + reason + '）', g.pageRevision || 0);
    chrome.runtime.sendMessage({ type: 'bridge-status', payload: { error: '调试会话已结束：' + reason } }).catch(() => {});
  }
});

chrome.tabs.onRemoved.addListener(async (tabId) => {
  const g = await grant();
  const c = await control();
  if (g && g.tabId === tabId && c) {
    c.state = 'SUSPENDED';
    await set(KEYS.control, c);
    sendEvent(c.controlSessionId, 'tab_closed', '标签页已关闭', g.pageRevision || 0);
    await remove(KEYS.grant);
  }
});

// ---------------- 与侧边栏 / 引导页通信 ----------------

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  (async () => {
    switch (message.type) {
      case 'get-state':
        sendResponse(await buildState());
        break;
      case 'set-platform':
        await set(KEYS.platform, String(message.url || '').replace(/\/+$/, ''));
        await connect().catch(() => {});
        sendResponse(await buildState());
        break;
      case 'pair':
        try {
          const created = await startPairing();
          sendResponse({ ok: true, userCode: created.userCode });
        } catch (e) {
          sendResponse({ ok: false, message: e.message });
        }
        break;
      case 'unpair':
        await unpair();
        sendResponse(await buildState());
        break;
      case 'grant-tab':
        sendResponse(await grantCurrentTab());
        break;
      case 'pause':
        await setState('HUMAN_CONTROL', '用户在本机暂停');
        sendResponse(await buildState());
        break;
      case 'resume':
        await setState('AGENT_CONTROL', null);
        sendResponse(await buildState());
        break;
      case 'revoke':
        await setState('REVOKED', '用户撤销授权');
        await remove(KEYS.grant);
        sendResponse(await buildState());
        break;
      case 'locate-tab': {
        const g = await grant();
        if (g) {
          await chrome.tabs.update(g.tabId, { active: true }).catch(() => {});
          await chrome.windows.update((await chrome.tabs.get(g.tabId)).windowId, { focused: true }).catch(() => {});
        }
        sendResponse(await buildState());
        break;
      }
      default:
        sendResponse({ ok: false, message: 'unknown message' });
    }
  })();
  return true;
});

async function setState(nextState, message) {
  const c = (await control()) || {};
  const g = await grant();
  const controlSessionId = c.controlSessionId || (g && g.controlSessionId);
  c.state = nextState;
  c.leaseVersion = (c.leaseVersion || 1) + 1;
  await set(KEYS.control, c);
  if (controlSessionId) {
    const event = nextState === 'AGENT_CONTROL' ? 'resumed' : nextState === 'HUMAN_CONTROL' ? 'paused' : 'revoked';
    sendEvent(controlSessionId, event, message, g ? g.pageRevision || 0 : 0);
    // 暂停/撤销必须让服务端的租期也失效（下次 Agent 直接用新 lease）
    const base = await platformUrl();
    const { accessToken } = await auth();
    if (base && accessToken && nextState !== 'AGENT_CONTROL') {
      const path = nextState === 'HUMAN_CONTROL' ? 'pause' : '';
      const url = path
        ? `${base}/api/browser/extension/control-sessions/${controlSessionId}/${path}`
        : `${base}/api/browser/extension/control-sessions/${controlSessionId}`;
      await fetch(url, {
        method: path ? 'POST' : 'DELETE',
        headers: { Authorization: 'Bearer ' + accessToken },
      }).catch(() => {});
    }
  }
}

async function grantCurrentTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab || !tab.url) {
    return { ok: false, message: '没有可授权的标签页' };
  }
  const policy = checkUrl(tab.url);
  if (!policy.ok) {
    return { ok: false, message: policy.reason };
  }
  const pending = (await get(KEYS.pending)) || null;
  const c = (await control()) || {};
  const controlSessionId = pending && pending.controlSessionId ? pending.controlSessionId : c.controlSessionId;
  if (!controlSessionId) {
    return { ok: false, message: '还没有任务请求浏览器。先在左侧对话里让 Agent 打开一个网页。' };
  }
  const g = {
    tabId: tab.id,
    url: tab.url,
    origin: new URL(tab.url).origin,
    title: tab.title || '',
    controlSessionId,
    pageRevision: (c.pageRevision || 0) + 1,
  };
  await set(KEYS.grant, g);
  // 授权后默认人类控制，由用户点「交还」才交给 Agent（方案 10.1）
  await set(KEYS.control, { controlSessionId, state: 'HUMAN_CONTROL', leaseVersion: (c.leaseVersion || 1) + 1,
    pageRevision: g.pageRevision });
  sendTabState(controlSessionId, { ...g, controlState: 'HUMAN_CONTROL' });
  await remove(KEYS.pending);

  // 任务里带了目标网址且与当前页不同 → 顺便导航过去（读操作，不触发写动作）
  if (pending && pending.url && pending.url !== tab.url) {
    try {
      await attach(tab.id);
      const state = await navigate(tab.id, pending.url);
      g.url = state.url;
      g.origin = new URL(state.url).origin;
      g.title = state.title;
      g.pageRevision += 1;
      await set(KEYS.grant, g);
      sendTabState(controlSessionId, { ...g, controlState: 'HUMAN_CONTROL' });
    } catch (e) {
      return { ok: true, message: '已授权，但打开目标网址失败：' + e.message };
    }
  }
  return { ok: true, state: await buildState() };
}

async function buildState() {
  const base = await platformUrl();
  const { accessToken, device } = await auth();
  const g = await grant();
  const c = await control();
  const pending = await get(KEYS.pending);
  return {
    platform: base,
    paired: !!(accessToken && device && device.id),
    device,
    connection: status(),
    grant: g,
    control: c,
    pending,
    version: chrome.runtime.getManifest().version,
  };
}
