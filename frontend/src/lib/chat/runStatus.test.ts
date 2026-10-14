import { describe, expect, it } from "vitest";
import { lastRunHint, runStatusLabel } from "./runStatus";
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

/**
 * 规则（用户反馈后两次收紧，最终定稿）：**只在「已中断 / 出错」时提示**。
 * 「已完成」一律不提示 —— 答案已在聊天记录里，再挂一句就是噪音。
 */
describe("lastRunHint", () => {
  it("进行中不提示（页面本来就在实时显示）", () => {
    expect(lastRunHint(run({ status: "RUNNING" }))).toBeNull();
    expect(lastRunHint(null)).toBeNull();
  });

  it("已完成一律不提示（无论摘要有没有值）", () => {
    expect(lastRunHint(run({ status: "FINISHED", textSummary: "写完了" }))).toBeNull();
    expect(lastRunHint(run({ status: "FINISHED" }))).toBeNull();
  });

  it("中断/出错一律提示并说明原因", () => {
    expect(lastRunHint(run({ status: "INTERRUPTED", error: "服务重启，这一轮未跑完" }))).toBe(
      "上一轮：已中断（服务重启，这一轮未跑完）",
    );
    expect(lastRunHint(run({ status: "ERROR", error: "模型 404" }))).toBe("上一轮：出错（模型 404）");
    // 没有原因时也要给一句，别是空白
    expect(lastRunHint(run({ status: "INTERRUPTED" }))).toBe("上一轮：已中断");
  });
});
