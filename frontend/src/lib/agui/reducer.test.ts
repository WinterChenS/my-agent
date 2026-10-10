import { describe, expect, it } from "vitest";
import { initialChatState, reduceEvent, replayMessages } from "@/lib/agui/reducer";
import type { AguiEventLike, ChatState } from "@/lib/agui/types";

function applyAll(events: AguiEventLike[]): ChatState {
  return events.reduce(reduceEvent, initialChatState);
}

describe("reduceEvent：TEXT_MESSAGE_*", () => {
  it("按 messageId 归并流式文本", () => {
    const state = applyAll([
      { type: "RUN_STARTED", runId: "r1" },
      { type: "TEXT_MESSAGE_START", messageId: "m1" },
      { type: "TEXT_MESSAGE_CONTENT", messageId: "m1", delta: "你" },
      { type: "TEXT_MESSAGE_CONTENT", messageId: "m1", delta: "好" },
      { type: "TEXT_MESSAGE_END", messageId: "m1" },
      { type: "RUN_FINISHED" },
    ]);

    expect(state.status).toBe("finished");
    expect(state.items).toHaveLength(1);
    const item = state.items[0];
    expect(item.kind).toBe("text");
    if (item.kind === "text") {
      expect(item.content).toBe("你好");
      expect(item.streaming).toBe(false);
    }
  });

  it("缺少 START 的 CONTENT 也能兜住内容", () => {
    const state = applyAll([
      { type: "RUN_STARTED" },
      { type: "TEXT_MESSAGE_CONTENT", messageId: "m9", delta: "孤立片段" },
    ]);
    expect(state.items).toHaveLength(1);
    const item = state.items[0];
    if (item.kind === "text") {
      expect(item.content).toBe("孤立片段");
    }
  });
});

describe("reduceEvent：TOOL_CALL_*", () => {
  it("归并工具调用参数与结果", () => {
    const state = applyAll([
      { type: "RUN_STARTED" },
      { type: "TOOL_CALL_START", toolCallId: "t1", toolCallName: "read_file" },
      { type: "TOOL_CALL_ARGS", toolCallId: "t1", delta: '{"path":' },
      { type: "TOOL_CALL_ARGS", toolCallId: "t1", delta: '"a.txt"}' },
      { type: "TOOL_CALL_END", toolCallId: "t1" },
      { type: "TOOL_CALL_RESULT", toolCallId: "t1", result: "hello" },
      { type: "RUN_FINISHED" },
    ]);

    expect(state.items).toHaveLength(1);
    const item = state.items[0];
    expect(item.kind).toBe("tool");
    if (item.kind === "tool") {
      expect(item.name).toBe("read_file");
      expect(item.args).toBe('{"path":"a.txt"}');
      expect(item.result).toBe("hello");
      expect(item.state).toBe("done");
    }
  });
});

describe("reduceEvent：CUSTOM 卡片映射", () => {
  it("confirm_card / mapping_card 映射为对应卡片", () => {
    const state = applyAll([
      { type: "RUN_STARTED" },
      { type: "CUSTOM", name: "confirm_card", value: { title: "确认", options: [] } },
      { type: "CUSTOM", name: "mapping_card", value: { title: "映射", columns: [], rows: [] } },
      { type: "RUN_FINISHED" },
    ]);
    expect(state.items.map((i) => (i.kind === "card" ? i.card : null))).toEqual([
      "confirm",
      "mapping",
    ]);
  });

  it("token_usage 不产生可视卡片", () => {
    const state = applyAll([
      { type: "RUN_STARTED" },
      { type: "CUSTOM", name: "token_usage", value: { total_tokens: 12 } },
      { type: "RUN_FINISHED" },
    ]);
    expect(state.items).toHaveLength(0);
  });

  it("未知 CUSTOM 降级为 unknown 卡而不是崩溃", () => {
    const state = applyAll([
      { type: "RUN_STARTED" },
      { type: "CUSTOM", name: "something_new", value: { a: 1 } },
    ]);
    expect(state.items).toHaveLength(1);
    const item = state.items[0];
    if (item.kind === "card") {
      expect(item.card).toBe("unknown");
      expect(item.name).toBe("something_new");
    }
  });
});

describe("reduceEvent：错误与状态", () => {
  it("RUN_ERROR 追加错误项并置为 error", () => {
    const state = applyAll([
      { type: "RUN_STARTED" },
      { type: "RUN_ERROR", message: "模型不可用", code: "RUN_FAILED" },
    ]);
    expect(state.status).toBe("error");
    expect(state.items).toHaveLength(1);
    const item = state.items[0];
    if (item.kind === "error") {
      expect(item.message).toBe("模型不可用");
      expect(item.code).toBe("RUN_FAILED");
    }
  });

  it("STATE_* 等未声明事件不改变展示树", () => {
    const before = applyAll([{ type: "RUN_STARTED" }]);
    const after = reduceEvent(before, { type: "STATE_SNAPSHOT" });
    expect(after).toBe(before);
  });

  it("新一轮 RUN_STARTED 重置消息树", () => {
    const first = applyAll([
      { type: "RUN_STARTED" },
      { type: "TEXT_MESSAGE_START", messageId: "m1" },
      { type: "TEXT_MESSAGE_CONTENT", messageId: "m1", delta: "旧内容" },
    ]);
    const second = reduceEvent(first, { type: "RUN_STARTED", runId: "r2" });
    expect(second.items).toHaveLength(0);
    expect(second.status).toBe("running");
  });
});

describe("replayMessages：历史回放", () => {
  it("按角色还原气泡并跳过空内容", () => {
    const state = replayMessages([
      { role: "USER", content: "你好" },
      { role: "ASSISTANT", content: "在的" },
      { role: "TOOL", content: null },
    ]);
    expect(state.items).toHaveLength(2);
    expect(state.items.map((i) => (i.kind === "text" ? i.role : null))).toEqual([
      "user",
      "assistant",
    ]);
  });
});
