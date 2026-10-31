import { ERR } from './protocol.js';

const $ = (id) => document.getElementById(id);

let view = null;

async function refresh() {
  view = await chrome.runtime.sendMessage({ type: 'get-state' });
  render();
}

function badge(state) {
  const el = $('state-badge');
  el.className = 'badge';
  if (!state) {
    el.textContent = '未授权';
    return;
  }
  if (state === 'AGENT_CONTROL') {
    el.textContent = 'Agent 控制中';
    el.classList.add('agent');
  } else if (state === 'HUMAN_CONTROL') {
    el.textContent = '你在控制';
    el.classList.add('human');
  } else if (state === 'AWAITING_TAB') {
    el.textContent = '等待授权';
  } else if (state === 'SUSPENDED' || state === 'REVOKED') {
    el.textContent = state === 'SUSPENDED' ? '已暂停' : '已撤销';
    el.classList.add('suspended');
  } else {
    el.textContent = state;
  }
}

function render() {
  if (!view) return;
  const dot = $('dot');
  dot.className = 'dot' + (view.connection && view.connection.connected ? ' on' : '');
  $('version').textContent = 'v' + view.version;

  const alert = $('alert');
  alert.innerHTML = '';
  if (view.pending && view.pending.url) {
    const div = document.createElement('div');
    div.className = 'warn';
    div.textContent = '任务请求操作：' + view.pending.url + '。请切到目标页面后点下面的「授权当前标签页」。';
    alert.appendChild(div);
  }

  const conn = $('connection');
  conn.innerHTML = '';
  const platformRow = document.createElement('div');
  platformRow.className = 'row';
  platformRow.innerHTML = '<span>平台</span><span class="mono">' +
    (view.platform ? escapeHtml(view.platform) : '未设置') + '</span>';
  conn.appendChild(platformRow);
  const connRow = document.createElement('div');
  connRow.className = 'row';
  connRow.innerHTML = '<span>连接</span><span>' +
    (view.connection && view.connection.connected ? '已连接' : '未连接') + '</span>';
  conn.appendChild(connRow);

  const g = view.grant;
  badge(view.control ? view.control.state : g ? 'AWAITING_TAB' : null);
  $('tab-info').textContent = g ? (g.title || '') + '\n' + (g.url || '') : '还没有授权任何标签页';

  const actions = $('actions');
  actions.innerHTML = '';
  const add = (label, cls, handler, disabled) => {
    const b = document.createElement('button');
    b.textContent = label;
    if (cls) b.className = cls;
    b.disabled = !!disabled;
    b.onclick = async () => {
      b.disabled = true;
      await handler();
      await refresh();
    };
    actions.appendChild(b);
  };

  if (!g || (view.pending && view.pending.url)) {
    add('授权当前标签页', 'primary', async () => {
      const result = await chrome.runtime.sendMessage({ type: 'grant-tab' });
      if (result && !result.ok) {
        showError(result.message);
      }
    });
  }
  const state = view.control ? view.control.state : null;
  if (state === 'HUMAN_CONTROL') {
    add('交还给 Agent', 'primary', () => chrome.runtime.sendMessage({ type: 'resume' }));
  } else if (state === 'AGENT_CONTROL') {
    add('暂停（我要自己操作）', '', () => chrome.runtime.sendMessage({ type: 'pause' }));
  } else if (state === 'SUSPENDED') {
    add('重新授权当前标签页', 'primary', async () => {
      const result = await chrome.runtime.sendMessage({ type: 'grant-tab' });
      if (result && !result.ok) showError(result.message);
    });
  }
  if (g) {
    add('定位标签页', 'ghost', () => chrome.runtime.sendMessage({ type: 'locate-tab' }));
    add('撤销授权', 'ghost', () => chrome.runtime.sendMessage({ type: 'revoke' }));
  }

  $('device-badge').textContent = view.paired ? '已配对' : '未配对';
  $('device-info').textContent = view.device && view.device.id
    ? view.device.id + '\ncredential v' + (view.device.credentialVersion || 1)
    : '—';
}

function showError(message) {
  const alert = $('alert');
  alert.innerHTML = '';
  const div = document.createElement('div');
  div.className = 'err';
  div.textContent = message || '操作失败';
  alert.appendChild(div);
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

$('btn-onboarding').onclick = () => chrome.tabs.create({ url: 'onboarding.html' });

chrome.runtime.onMessage.addListener((message) => {
  if (message.type === 'bridge-status' || message.type === 'needs-grant' || message.type === 'command-done') {
    refresh();
  }
});

refresh();
setInterval(refresh, 3000);
