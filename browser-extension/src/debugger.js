// chrome.debugger 封装：只暴露固定动作，**不接受后端传来的 CDP 方法名或任意 JS**。
// 所有页面内取值都走本文件里写死的模板函数，参数以 JSON 数据传入（不拼进代码）。
import { ERR } from './protocol.js';

export async function attach(tabId) {
  try {
    await chrome.debugger.attach({ tabId }, '1.3');
  } catch (e) {
    const message = String(e && e.message ? e.message : e);
    if (!/already attached|Another debugger/i.test(message)) {
      throw new BridgeError(ERR.DEBUGGER_DETACHED, `无法接管标签页调试会话：${message}`);
    }
  }
}

export async function detach(tabId) {
  try {
    await chrome.debugger.detach({ tabId });
  } catch {
    /* 已经 detach 了 */
  }
}

export function send(tabId, method, params = {}) {
  return chrome.debugger.sendCommand({ tabId }, method, params);
}

export class BridgeError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

/** 页面内固定模板（审阅过、随扩展打包；不接收远程代码）。 */
const SNIPPETS = {
  snapshot: `function(args){
    const maxChars = Number(args.maxChars) || 4000;
    document.querySelectorAll('[data-myagent-ref]').forEach(function(e){ e.removeAttribute('data-myagent-ref'); });
    const out = [];
    let n = 0;
    const sel = 'a[href],button,input,textarea,select,[role="button"],[role="link"],[role="checkbox"],[contenteditable="true"]';
    const nodes = document.querySelectorAll(sel);
    for (let i = 0; i < nodes.length; i++) {
      const el = nodes[i];
      const r = el.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) continue;
      const cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none' || cs.opacity === '0') continue;
      if (r.bottom < 0 || r.top > window.innerHeight * 4) continue;
      const raw = el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.name || '';
      const label = String(raw).replace(/\\s+/g, ' ').trim().slice(0, 120);
      if (!label && el.tagName === 'INPUT') continue;
      n += 1;
      const ref = 'el-' + n;
      el.setAttribute('data-myagent-ref', ref);
      out.push({ ref: ref, tag: el.tagName.toLowerCase(), type: el.type || '', text: label,
        x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) });
      if (out.length >= 250) break;
    }
    return { url: location.href, title: document.title,
      text: (document.body ? document.body.innerText : '').slice(0, maxChars), elements: out };
  }`,

  center: `function(args){
    const ref = String(args.elementRef || '');
    let el = null;
    if (ref) el = document.querySelector('[data-myagent-ref="' + (window.CSS && CSS.escape ? CSS.escape(ref) : ref) + '"]');
    if (!el) return null;
    el.scrollIntoView({ block: 'center', inline: 'center' });
    const r = el.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, tag: el.tagName.toLowerCase(), text: (el.innerText || '').slice(0, 80) };
  }`,

  focus: `function(args){
    const ref = String(args.elementRef || '');
    let el = null;
    if (ref) el = document.querySelector('[data-myagent-ref="' + (window.CSS && CSS.escape ? CSS.escape(ref) : ref) + '"]');
    if (!el && args.selector) { try { el = document.querySelector(String(args.selector)); } catch (e) { return null; } }
    if (!el) return null;
    el.scrollIntoView({ block: 'center' });
    if (el.focus) el.focus();
    const r = el.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  }`,

  readyState: `function(){ return { state: document.readyState, url: location.href, title: document.title, text: document.body ? document.body.innerText.slice(0, 4000) : '' }; }`,

  pick: `function(args){
    const want = String(args.text || '');
    const nodes = document.querySelectorAll('a,button,[role="button"],li,h1,h2,h3');
    for (let i = 0; i < nodes.length; i++) {
      const el = nodes[i];
      const t = (el.innerText || '').trim();
      if (t && t.indexOf(want) >= 0) {
        el.scrollIntoView({ block: 'center' });
        const r = el.getBoundingClientRect();
        return { x: r.x + r.width / 2, y: r.y + r.height / 2, matched: t.slice(0, 80) };
      }
    }
    return null;
  }`,
};

/**
 * 在页面里执行扩展自带的模板。
 * @param {number} tabId
 * @param {string} name SNIPPETS 里的名字
 * @param {object} args 作为 JSON 数据传入（不会拼进源码）
 */
export async function snippet(tabId, name, args = {}) {
  const src = SNIPPETS[name];
  if (!src) {
    throw new BridgeError(ERR.POLICY_BLOCKED, `未知的页面模板：${name}`);
  }
  const expression = '(' + src + ')(' + JSON.stringify(args) + ')';
  const result = await send(tabId, 'Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: false,
    userGesture: true,
  });
  if (result.exceptionDetails) {
    throw new BridgeError(ERR.STALE_PAGE, '页面脚本执行失败：' + (result.exceptionDetails.text || '未知错误'));
  }
  return result.result ? result.result.value : null;
}

export async function clickAt(tabId, x, y) {
  const base = { x, y, button: 'left', clickCount: 1 };
  await send(tabId, 'Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
  await send(tabId, 'Input.dispatchMouseEvent', { type: 'mousePressed', ...base });
  await send(tabId, 'Input.dispatchMouseEvent', { type: 'mouseReleased', ...base });
}

export async function typeText(tabId, text) {
  await send(tabId, 'Input.insertText', { text });
}

const KEY_CODES = {
  Enter: { windowsVirtualKeyCode: 13, key: 'Enter', code: 'Enter' },
  Tab: { windowsVirtualKeyCode: 9, key: 'Tab', code: 'Tab' },
  Escape: { windowsVirtualKeyCode: 27, key: 'Escape', code: 'Escape' },
  Backspace: { windowsVirtualKeyCode: 8, key: 'Backspace', code: 'Backspace' },
  ArrowDown: { windowsVirtualKeyCode: 40, key: 'ArrowDown', code: 'ArrowDown' },
  ArrowUp: { windowsVirtualKeyCode: 38, key: 'ArrowUp', code: 'ArrowUp' },
};

export async function pressKey(tabId, key) {
  const spec = KEY_CODES[key] || { key, code: key, windowsVirtualKeyCode: key.length === 1 ? key.toUpperCase().charCodeAt(0) : 0 };
  await send(tabId, 'Input.dispatchKeyEvent', { type: 'keyDown', ...spec });
  if (key.length === 1) {
    await send(tabId, 'Input.dispatchKeyEvent', { type: 'char', text: key, ...spec });
  }
  await send(tabId, 'Input.dispatchKeyEvent', { type: 'keyUp', ...spec });
}

export async function scroll(tabId, deltaY) {
  await send(tabId, 'Input.dispatchMouseEvent', {
    type: 'mouseWheel', x: 200, y: 200, deltaX: 0, deltaY: Number(deltaY) || 600,
  });
}

export async function navigate(tabId, url, timeoutMs = 20000) {
  await send(tabId, 'Page.enable');
  await send(tabId, 'Page.navigate', { url });
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 400));
    try {
      const state = await snippet(tabId, 'readyState');
      if (state && state.state === 'complete' && state.url && state.url !== 'about:blank') {
        return state;
      }
    } catch {
      /* 导航中页面上下文会短暂销毁，继续等 */
    }
  }
  throw new BridgeError(ERR.OUTCOME_UNKNOWN, '页面在 ' + Math.round(timeoutMs / 1000) + ' 秒内没有加载完成');
}

export async function screenshot(tabId) {
  const result = await send(tabId, 'Page.captureScreenshot', { format: 'jpeg', quality: 70 });
  return result.data;
}
