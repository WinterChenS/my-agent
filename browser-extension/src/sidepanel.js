const $ = (id) => document.getElementById(id);

let view = null;
let screencasting = false;
let frameSize = { width: 0, height: 0 };

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
  $('auto-grant').checked = !!view.autoGrant;

  const alert = $('alert');
  alert.innerHTML = '';
  if (view.pending && view.pending.url && !view.autoGrant) {
    const div = document.createElement('div');
    div.className = 'warn';
    div.textContent = '任务请求操作：' + view.pending.url + '。点下面「授权这个标签页」后 Agent 会直接开始；'
      + '不想每次点就打开上面的「自动接管」。';
    alert.appendChild(div);
  }

  const conn = $('connection');
  conn.innerHTML = '';
  const rows = [
    ['平台', view.platform || '未设置'],
    ['连接', view.connection && view.connection.connected ? '已连接' : '未连接'],
  ];
  rows.forEach(([k, v]) => {
    const row = document.createElement('div');
    row.className = 'row';
    row.innerHTML = '<span>' + k + '</span><span class="mono">' + escapeHtml(String(v)) + '</span>';
    conn.appendChild(row);
  });

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

  const state = view.control ? view.control.state : null;
  if (!g) {
    add('授权这个标签页（Agent 直接开始）', 'primary', async () => {
      const result = await chrome.runtime.sendMessage({ type: 'grant-tab' });
      if (result && !result.ok) showError(result.message);
    });
  }
  if (state === 'HUMAN_CONTROL') {
    add('交还给 Agent', 'primary', () => chrome.runtime.sendMessage({ type: 'resume' }));
  } else if (state === 'AGENT_CONTROL') {
    add('暂停（我要自己操作）', '', () => chrome.runtime.sendMessage({ type: 'pause' }));
  }
  if (g) {
    add('撤销授权', 'ghost', () => chrome.runtime.sendMessage({ type: 'revoke' }));
  }

  $('device-badge').textContent = view.paired ? '已配对' : '未配对';
  $('device-info').textContent = view.device && view.device.id
    ? view.device.id + '\ncredential v' + (view.device.credentialVersion || 1)
    : '—';

  updateScreenBadge();
}

function updateScreenBadge() {
  const badgeEl = $('screen-badge');
  badgeEl.textContent = screencasting ? '镜像中' : '未开始';
  badgeEl.className = 'badge' + (screencasting ? ' agent' : '');
  $('screen-empty').style.display = screencasting && frameSize.width > 0 ? 'none' : 'block';
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

// ---------------- 画面 ----------------

$('btn-screen').onclick = async () => {
  const result = await chrome.runtime.sendMessage({ type: 'screencast-start' });
  if (!result || !result.ok) {
    showError((result && result.message) || '启动画面失败');
    return;
  }
  screencasting = true;
  updateScreenBadge();
};

$('btn-screen-stop').onclick = async () => {
  await chrome.runtime.sendMessage({ type: 'screencast-stop' });
  screencasting = false;
  frameSize = { width: 0, height: 0 };
  const canvas = $('screen');
  canvas.getContext('2d').clearRect(0, 0, canvas.width, canvas.height);
  updateScreenBadge();
};

$('btn-locate').onclick = () => chrome.runtime.sendMessage({ type: 'locate-tab' });

$('auto-grant').onchange = async (event) => {
  await chrome.runtime.sendMessage({ type: 'set-auto-grant', enabled: event.target.checked });
  await refresh();
};

const canvas = $('screen');

chrome.runtime.onMessage.addListener((message) => {
  if (message.type === 'screencast-frame') {
    renderFrame(message);
    return;
  }
  if (message.type === 'bridge-status' || message.type === 'needs-grant' || message.type === 'command-done') {
    refresh();
  }
});

let pendingFrame = null;
function renderFrame(message) {
  const image = new Image();
  image.onload = () => {
    frameSize = { width: message.width || image.width, height: message.height || image.height };
    if (canvas.width !== image.width || canvas.height !== image.height) {
      canvas.width = image.width;
      canvas.height = image.height;
    }
    canvas.getContext('2d').drawImage(image, 0, 0);
    if (!screencasting) {
      screencasting = true;
    }
    updateScreenBadge();
  };
  image.src = 'data:image/jpeg;base64,' + message.data;
  pendingFrame = message;
}

/** 画面用 object-fit: contain 显示，点击要换算回页面坐标（否则点偏）。 */
function toPagePoint(event) {
  const rect = canvas.getBoundingClientRect();
  if (!canvas.width || !canvas.height) {
    return null;
  }
  const scale = Math.min(rect.width / canvas.width, rect.height / canvas.height);
  const drawW = canvas.width * scale;
  const drawH = canvas.height * scale;
  const offsetX = (rect.width - drawW) / 2;
  const offsetY = (rect.height - drawH) / 2;
  const x = (event.clientX - rect.left - offsetX) / scale;
  const y = (event.clientY - rect.top - offsetY) / scale;
  if (x < 0 || y < 0 || x > canvas.width || y > canvas.height) {
    return null;
  }
  return { x: Math.round(x), y: Math.round(y) };
}

canvas.addEventListener('click', async (event) => {
  const point = toPagePoint(event);
  if (!point) return;
  canvas.focus();
  await chrome.runtime.sendMessage({ type: 'screencast-input', input: { type: 'click', ...point } });
});

canvas.addEventListener('wheel', async (event) => {
  event.preventDefault();
  await chrome.runtime.sendMessage({
    type: 'screencast-input',
    input: { type: 'scroll', deltaY: Math.round(event.deltaY) },
  });
}, { passive: false });

canvas.addEventListener('keydown', async (event) => {
  const keys = ['Enter', 'Tab', 'Escape', 'Backspace', 'ArrowDown', 'ArrowUp'];
  if (event.key.length === 1) {
    await chrome.runtime.sendMessage({ type: 'screencast-input', input: { type: 'text', text: event.key } });
    event.preventDefault();
    return;
  }
  if (keys.includes(event.key)) {
    await chrome.runtime.sendMessage({ type: 'screencast-input', input: { type: 'key', key: event.key } });
    event.preventDefault();
  }
});

$('btn-onboarding').onclick = () => chrome.tabs.create({ url: 'onboarding.html' });

refresh();
setInterval(refresh, 3000);
