import { describe, expect, it } from "vitest";
import { isAnswerVisible, lastRunHint, runStatusLabel } from "./runStatus";
import type { RunHistoryItem } from "@/api/sessions";

function run(partial: Partial<RunHistoryItem>): RunHistoryItem {
  return {
    id: 1,
    runId: "r",
    status: "FINISHED",
    startedAt: null,
    endedAt: null,
    ...partial,
  };
}

describe("runStatusLabel", () => {
  it("四种状态都有中文文案", () => {
    expect(runStatusLabel("RUNNING")).toBe("进行中");
    expect(runStatusLabel("FINISHED")).toBe("已完成");
    expect(runStatusLabel("INTERRUPTED")).toBe("已中断");
    expect(runStatusLabel("ERROR")).toBe("出错");
  });
});

describe("lastRunHint", () => {
  it("进行中不提示（页面会直接显示实时内容）", () => {
    expect(lastRunHint(run({ status: "RUNNING" }))).toBeNull();
    expect(lastRunHint(null)).toBeNull();
  });

  it("已完成的答案已经摆在聊天记录里 → 不提示（用户反馈这里不该有噪音）", () => {
    expect(
      lastRunHint(run({ status: "FINISHED", textSummary: "写完了" }), { answerVisible: true }),
    ).toBeNull();
  });

  it("已完成但答案还看不见 → 提示（重启后能看到上一轮跑出了什么）", () => {
    expect(
      lastRunHint(run({ status: "FINISHED", textSummary: "写完了" }), { answerVisible: false }),
    ).toBe("上一轮：已完成 · 写完了");
    expect(lastRunHint(run({ status: "FINISHED" }), { answerVisible: false })).toBe("上一轮：已完成");
  });

  it("中断/出错一律提示并说明原因（这是用户最需要知道的信息）", () => {
    expect(
      lastRunHint(run({ status: "INTERRUPTED", error: "服务重启，这一轮未跑完" })),
    ).toBe("上一轮：已中断（服务重启，这一轮未跑完）");
    expect(lastRunHint(run({ status: "ERROR", error: "模型 404" }))).toBe("上一轮：出错（模型 404）");
    // 即使答案看起来可见，中断/出错也要提示
    expect(
      lastRunHint(run({ status: "INTERRUPTED", error: "服务重启" }), { answerVisible: true }),
    ).toBe("上一轮：已中断（服务重启）");
  });
});

describe("isAnswerVisible", () => {
  it("聊天记录里有晚于该轮开始时间的助手消息 → 答案可见", () => {
    const r = run({ startedAt: "2026-10-13T02:00:00Z" });
    expect(isAnswerVisible(r, ["2026-10-13T01:00:00Z", "2026-10-13T02:01:00Z"])).toBe(true);
  });

  it("助手消息都早于该轮开始 → 答案不可见", () => {
    const r = run({ startedAt: "2026-10-13T02:00:00Z" });
    expect(isAnswerVisible(r, ["2026-10-13T01:00:00Z"])).toBe(false);
  });

  it("没有开始时间或没有助手消息 → 不可见（宁可多提示一次）", () => {
    expect(isAnswerVisible(run({ startedAt: null }), ["2026-10-13T02:01:00Z"])).toBe(false);
    expect(isAnswerVisible(run({ startedAt: "2026-10-13T02:00:00Z" }), [])).toBe(false);
  });
});
