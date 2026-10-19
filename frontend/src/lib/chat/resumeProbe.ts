/**
 * 「刷新/切页回来后接续正在跑的 run」的第一步：探测这一轮**是否在跑**。
 *
 * <p>为什么单次探测不可信（这是一个真实缺陷的根因）：刷新页面会**中断**还在途的
 * `POST /agui/run`，而服务端是"收到请求 → 解析 Agent → `RunningRunRegistry.beginRun`"
 * 才登记这一轮。于是"刷新那一刻"前端探到的 `running` 仍然是 `false`。
 * 原先的代码一次判定就 `return`，界面从此永远停在「只有上一轮答案、没有本轮气泡、也没有停止按钮」，
 * 而且**没有任何东西会再触发接续**（实测：全量跑时用例报「助手气泡 toHaveCount(2) 实得 1」，
 * 而单独跑时后端更快、恰好登记完了，所以一直是绿的）。
 *
 * <p>所以这里给一个有界的**重试窗口**：窗口内只要探到在跑就立刻返回；窗口用尽仍没探到，
 * 才认定"没在跑"。窗口只影响"确实没在跑"的会话（多几次轻量 GET），对正常路径零延迟。
 */
export async function probeRunningRun<T>(
  probe: () => Promise<T | null>,
  isRunning: (state: T) => boolean,
  options: {
    /** 最多探测几次（含第一次，立即执行）。 */
    attempts?: number;
    /** 重试间隔（毫秒），仅对第 2 次及以后生效。 */
    intervalMs?: number;
    /** 接续令牌校验：返回 false（用户已切走/又有新接续）就立刻放弃，不再发请求。 */
    active?: () => boolean;
    /** 便于单测注入，默认 setTimeout。 */
    sleep?: (ms: number) => Promise<void>;
  } = {},
): Promise<T | null> {
  const attempts = options.attempts ?? 10;
  const intervalMs = options.intervalMs ?? 1000;
  const active = options.active ?? (() => true);
  const sleep =
    options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));

  for (let attempt = 0; attempt < attempts; attempt += 1) {
    if (attempt > 0) {
      await sleep(intervalMs);
      if (!active()) {
        return null;
      }
    }
    const state = await probe();
    if (state && isRunning(state)) {
      return state;
    }
  }
  return null;
}
