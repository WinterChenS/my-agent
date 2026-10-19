import { describe, expect, it, vi } from "vitest";
import { probeRunningRun } from "./resumeProbe";

type State = { running: boolean; text: string };

const probeOf = (results: (State | null)[]) => {
  let i = 0;
  return vi.fn(async () => results[Math.min(i++, results.length - 1)] ?? null);
};

/** 单测里不真等，直接把 sleep 记下来。 */
const instantSleep = () => vi.fn(async () => {});

describe("probeRunningRun", () => {
  it("第一次就探到在跑 → 立刻返回，不重试、不等待", async () => {
    const probe = probeOf([{ running: true, text: "hi" }]);
    const sleep = instantSleep();
    const state = await probeRunningRun(probe, (s) => s.running, { sleep, intervalMs: 1000 });
    expect(state).toEqual({ running: true, text: "hi" });
    expect(probe).toHaveBeenCalledTimes(1);
    expect(sleep).not.toHaveBeenCalled();
  });

  /**
   * 本次修复的核心：刷新页面时这一轮可能"还没登记完"，一次探测为 false。
   * 旧实现在这里就放弃了（界面永远缺本轮气泡），新实现必须重试到探到为止。
   */
  it("前几次没探到（run 还没登记）→ 重试，探到后返回", async () => {
    const probe = probeOf([
      { running: false, text: "" },
      null,
      { running: false, text: "" },
      { running: true, text: "stub" },
    ]);
    const sleep = instantSleep();
    const state = await probeRunningRun(probe, (s) => s.running, { sleep, intervalMs: 800 });
    expect(state).toEqual({ running: true, text: "stub" });
    expect(probe).toHaveBeenCalledTimes(4);
    // 第 2 次及以后才等待
    expect(sleep).toHaveBeenCalledTimes(3);
    expect(sleep).toHaveBeenCalledWith(800);
  });

  it("窗口用尽仍未在跑 → 返回 null（不会无限轮询）", async () => {
    const probe = probeOf([{ running: false, text: "" }]);
    const sleep = instantSleep();
    const state = await probeRunningRun(probe, (s) => s.running, {
      sleep,
      attempts: 3,
      intervalMs: 10,
    });
    expect(state).toBeNull();
    expect(probe).toHaveBeenCalledTimes(3);
  });

  it("用户已经切走（active=false）→ 立刻放弃，不再探测", async () => {
    const probe = probeOf([{ running: false, text: "" }]);
    const sleep = instantSleep();
    const state = await probeRunningRun(probe, (s) => s.running, {
      sleep,
      attempts: 10,
      active: () => false,
    });
    expect(state).toBeNull();
    expect(probe).toHaveBeenCalledTimes(1);
  });

  it("重试期间用户切走 → 停止重试并返回 null", async () => {
    let alive = true;
    const probe = probeOf([{ running: false, text: "" }]);
    const sleep = vi.fn(async () => {
      alive = false;
    });
    const state = await probeRunningRun(probe, (s) => s.running, {
      sleep,
      attempts: 10,
      active: () => alive,
    });
    expect(state).toBeNull();
    expect(probe).toHaveBeenCalledTimes(1);
  });
});
