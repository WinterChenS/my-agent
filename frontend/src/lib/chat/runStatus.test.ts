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

describe("lastRunHint", () => {
  it("进行中不提示（页面会直接显示实时内容）", () => {
    expect(lastRunHint(run({ status: "RUNNING" }))).toBeNull();
    expect(lastRunHint(null)).toBeNull();
  });

  it("已完成带摘要片段", () => {
    expect(lastRunHint(run({ status: "FINISHED", textSummary: "写完了" }))).toBe(
      "上一轮：已完成 · 写完了",
    );
    expect(lastRunHint(run({ status: "FINISHED" }))).toBe("上一轮：已完成");
  });

  it("中断/出错带原因（重启后能看到为什么）", () => {
    expect(
      lastRunHint(run({ status: "INTERRUPTED", error: "服务重启，这一轮未跑完" })),
    ).toBe("上一轮：已中断（服务重启，这一轮未跑完）");
    expect(lastRunHint(run({ status: "ERROR", error: "模型 404" }))).toBe(
      "上一轮：出错（模型 404）",
    );
  });
});
