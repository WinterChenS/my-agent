const $ = (id) => document.getElementById(id);

async function load() {
  const state = await chrome.runtime.sendMessage({ type: 'get-state' });
  $('platform').value = state.platform || 'http://192.168.3.10:18080';
  $('pair-result').textContent = state.paired
    ? '已配对，设备 ' + ((state.device && state.device.id) || '') + (state.connection && state.connection.connected
        ? '（已连接）' : '（未连接）')
    : '还没有配对';
}

$('save').onclick = async () => {
  const url = $('platform').value.trim();
  if (!/^https?:\/\//.test(url)) {
    $('pair-result').textContent = '平台地址要以 http:// 或 https:// 开头';
    return;
  }
  const state = await chrome.runtime.sendMessage({ type: 'set-platform', url });
  $('pair-result').textContent = state.paired
    ? '地址已保存。连接状态：' + (state.connection && state.connection.connected ? '已连接' : '未连接')
    : '地址已保存，接着点「开始配对」';
};

$('pair').onclick = async () => {
  $('pair-result').textContent = '正在创建配对…';
  const result = await chrome.runtime.sendMessage({ type: 'pair' });
  if (result.ok) {
    $('pair-result').textContent =
      '已打开平台配对页（配对码 ' + result.userCode + '）。请在页面里确认后回到这里，配对会自动完成。';
  } else {
    $('pair-result').textContent = '配对失败：' + result.message;
  }
};

$('sidepanel').onclick = () => chrome.tabs.create({ url: 'sidepanel.html' });

load();
