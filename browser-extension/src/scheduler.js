// 单标签页串行队列 + requestId 去重 + leaseVersion 校验（方案 10.1）。
import { ERR } from './protocol.js';

const seen = new Map(); // requestId -> { at, result }

export function remember(requestId, result) {
  seen.set(requestId, { at: Date.now(), result });
  if (seen.size > 200) {
    const oldest = [...seen.entries()].sort((a, b) => a[1].at - b[1].at).slice(0, 100);
    oldest.forEach(([key]) => seen.delete(key));
  }
}

export function recall(requestId) {
  return seen.get(requestId) || null;
}

/** 每个标签页一条串行链，避免两次点击交叉执行。 */
const queues = new Map();

export function enqueue(tabId, task) {
  const key = String(tabId);
  const previous = queues.get(key) || Promise.resolve();
  const next = previous.then(task, task);
  queues.set(key, next.catch(() => {}));
  return next;
}

/** 控制权/租期校验：任何一项不匹配都直接拒绝，不猜新元素、不重放。 */
export function checkLease(command, state) {
  if (!state || !state.controlSessionId || state.controlSessionId !== command.controlSessionId) {
    return { ok: false, code: ERR.TAB_NOT_AUTHORIZED, message: '这个标签页还没有授权给当前任务' };
  }
  if (state.state !== 'AGENT_CONTROL') {
    return { ok: false, code: ERR.HUMAN_CONTROL, message: '当前是本机用户控制（Agent 已暂停）' };
  }
  if (Number(command.leaseVersion) !== Number(state.leaseVersion)) {
    return { ok: false, code: ERR.COMMAND_EXPIRED, message: '控制权已变更，这条命令已失效' };
  }
  if (command.expiresAt && Date.parse(command.expiresAt) < Date.now()) {
    return { ok: false, code: ERR.COMMAND_EXPIRED, message: '命令已过期' };
  }
  return { ok: true };
}
