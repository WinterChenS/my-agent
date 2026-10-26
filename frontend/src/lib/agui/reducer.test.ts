import { describe, expect, it } from "vitest";
import { initialChatState, markApproval, reduceEvent, replayMessages } from "@/lib/agui/reducer";
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

  it("新一轮 RUN_STARTED 不清空历史（跨轮累积）", () => {
    const first = applyAll([
      { type: "RUN_STARTED" },
      { type: "TEXT_MESSAGE_START", messageId: "m1" },
      { type: "TEXT_MESSAGE_CONTENT", messageId: "m1", delta: "第一轮回复" },
      { type: "RUN_FINISHED" },
    ]);
    const second = reduceEvent(first, { type: "RUN_STARTED", runId: "r2" });
    // 回归：曾因 RUN_STARTED 重置，导致“回复后会话被清空”
    expect(second.items).toHaveLength(1);
    expect(second.status).toBe("running");
    expect(second.runId).toBe("r2");
  });

  it("新一轮 RUN_STARTED 保留已决定的审批卡（resume 后审批记录不丢）", () => {
    const decided = applyAll([
      { type: "RUN_STARTED" },
      {
        type: "RUN_FINISHED",
        outcome: {
          type: "interrupt",
          interrupts: [
            { id: "i1", reason: "tool_call", metadata: { toolName: "echo", toolInput: {} } },
          ],
        },
      } as unknown as AguiEventLike,
      { type: "TEXT_MESSAGE_START", messageId: "old" },
    ]);
    const approved = markApproval(decided, "i1", "approved");

    const next = reduceEvent(approved, { type: "RUN_STARTED", runId: "r2" });
    // 审批卡与历史文本都保留
    expect(next.items).toHaveLength(2);
    const kept = next.items.find((i) => i.kind === "approval");
    expect(kept?.kind).toBe("approval");
    if (kept?.kind === "approval") {
      expect(kept.status).toBe("approved");
    }
  });
});

describe("reduceEvent：RUN_FINISHED 的 interrupt（HITL 审批）", () => {
  const interruptRun = {
    type: "RUN_FINISHED",
    runId: "r1",
    outcome: {
      type: "interrupt",
      interrupts: [
        {
          id: "abc:call_1",
          reason: "tool_call",
          message: "Tool 'echo' requires user confirmation before execution",
          toolCallId: "call_1",
          metadata: { toolName: "echo", toolInput: { message: "hi" } },
        },
      ],
    },
  } as unknown as AguiEventLike;

  it("interrupt 生成审批卡并带上工具名与入参", () => {
    const state = applyAll([{ type: "RUN_STARTED" }, interruptRun]);

    expect(state.status).toBe("finished");
    expect(state.items).toHaveLength(1);
    const item = state.items[0];
    expect(item.kind).toBe("approval");
    if (item.kind === "approval") {
      expect(item.interruptId).toBe("abc:call_1");
      expect(item.toolName).toBe("echo");
      expect(item.args).toEqual({ message: "hi" });
      expect(item.status).toBe("pending");
    }
  });

  it("markApproval 把卡片置为已批准/已拒绝", () => {
    const state = applyAll([{ type: "RUN_STARTED" }, interruptRun]);
    const approved = markApproval(state, "abc:call_1", "approved");
    const item = approved.items[0];
    if (item.kind === "approval") {
      expect(item.status).toBe("approved");
    }
    const denied = markApproval(state, "abc:call_1", "denied");
    const deniedItem = denied.items[0];
    if (deniedItem.kind === "approval") {
      expect(deniedItem.status).toBe("denied");
    }
  });

  it("普通 RUN_FINISHED（无 interrupt）不产生审批卡", () => {
    const state = applyAll([{ type: "RUN_STARTED" }, { type: "RUN_FINISHED" }]);
    expect(state.status).toBe("finished");
    expect(state.items).toHaveLength(0);
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

describe("reduceEvent：子 Agent 事件（Phase 12 / D26）", () => {
  const sub = (name: string, value: Record<string, unknown>): AguiEventLike => ({
    type: "CUSTOM",
    name,
    value,
  });

  it("同 source 的 text 增量聚合成一条，不逐 delta 建卡", () => {
    const state = applyAll([
      sub("subagent.lifecycle", { source: "t1/general-purpose", type: "AGENT_START", name: "general-purpose" }),
      sub("subagent.text", { source: "t1/general-purpose", type: "TEXT_BLOCK_DELTA", delta: "播放" }),
      sub("subagent.text", { source: "t1/general-purpose", type: "TEXT_BLOCK_DELTA", delta: "商品" }),
      sub("subagent.text", { source: "t1/general-purpose", type: "TEXT_BLOCK_DELTA", delta: "。" }),
    ]);

    const items = state.items.filter((item) => item.kind === "subagent");
    expect(items).toHaveLength(1);
    expect(items[0].kind === "subagent" && items[0].text).toBe("播放商品。");
    expect(items[0].kind === "subagent" && items[0].status).toBe("running");
    expect(items[0].kind === "subagent" && items[0].name).toBe("general-purpose");
    // 不能再出现「未识别事件」那种 unknown 卡
    expect(state.items.some((item) => item.kind === "card" && item.card === "unknown")).toBe(false);
  });

  it("lifecycle 结束后状态变为已结束", () => {
    const state = applyAll([
      sub("subagent.lifecycle", { source: "t1/gp", type: "AGENT_START" }),
      sub("subagent.lifecycle", { source: "t1/gp", type: "AGENT_END" }),
    ]);
    const item = state.items[0];
    expect(item.kind === "subagent" && item.status).toBe("done");
  });

  it("工具调用按开始/结束计数并标记完成", () => {
    const state = applyAll([
      sub("subagent.tool_call", { source: "t1/gp", type: "TOOL_CALL_START", toolName: "read_file" }),
      sub("subagent.tool_call", { source: "t1/gp", type: "TOOL_CALL_START", toolName: "grep" }),
      sub("subagent.tool_call", { source: "t1/gp", type: "TOOL_CALL_END" }),
      sub("subagent.tool_result", { source: "t1/gp", type: "TOOL_RESULT_END" }),
    ]);
    const item = state.items[0];
    if (item.kind !== "subagent") {
      throw new Error("应为子 Agent 条目");
    }
    expect(item.toolCalls.map((call) => call.name)).toEqual(["read_file", "grep"]);
    expect(item.toolCalls.map((call) => call.done)).toEqual([true, true]);
  });

  it("两个子 Agent 按 source 各自成卡，互不混淆", () => {
    const state = applyAll([
      sub("subagent.text", { source: "t1/alpha", type: "TEXT_BLOCK_DELTA", delta: "A" }),
      sub("subagent.text", { source: "t1/beta", type: "TEXT_BLOCK_DELTA", delta: "B" }),
      sub("subagent.text", { source: "t1/alpha", type: "TEXT_BLOCK_DELTA", delta: "2" }),
    ]);
    const subs = state.items.filter((item) => item.kind === "subagent");
    expect(subs).toHaveLength(2);
    expect(subs[0].kind === "subagent" && subs[0].text).toBe("A2");
    expect(subs[1].kind === "subagent" && subs[1].text).toBe("B");
  });

  it("thinking 与 require_confirm 也不丢", () => {
    const state = applyAll([
      sub("subagent.thinking", { source: "t1/gp", type: "THINKING_BLOCK_DELTA", delta: "先想" }),
      sub("subagent.require_confirm", { source: "t1/gp", type: "REQUIRE_USER_CONFIRM", message: "要删文件吗" }),
    ]);
    const item = state.items[0];
    if (item.kind !== "subagent") {
      throw new Error("应为子 Agent 条目");
    }
    expect(item.thinking).toBe("先想");
    expect(item.confirm).toBe("要删文件吗");
  });
});

describe("reduceEvent：REASONING_*（思考过程）", () => {
  it("按 messageId 归并思考增量，结束后 streaming=false", () => {
    const state = applyAll([
      { type: "RUN_STARTED", runId: "r1" },
      { type: "REASONING_START" },
      { type: "REASONING_MESSAGE_START", messageId: "rs1" },
      { type: "REASONING_MESSAGE_CONTENT", messageId: "rs1", delta: "先看" },
      { type: "REASONING_MESSAGE_CONTENT", messageId: "rs1", delta: "需求" },
      { type: "REASONING_MESSAGE_END", messageId: "rs1" },
      { type: "REASONING_END" },
      { type: "RUN_FINISHED" },
    ]);

    const thinking = state.items.filter((item) => item.kind === "thinking");
    expect(thinking).toHaveLength(1);
    expect(thinking[0].kind === "thinking" && thinking[0].content).toBe("先看需求");
    // 折叠与否由前端组件根据 streaming 决定：结束后必须是 false
    expect(thinking[0].kind === "thinking" && thinking[0].streaming).toBe(false);
  });

  it("一段回复里可以有多段思考，顺序与到达顺序一致", () => {
    const state = applyAll([
      { type: "RUN_STARTED", runId: "r1" },
      { type: "REASONING_MESSAGE_START", messageId: "a" },
      { type: "REASONING_MESSAGE_CONTENT", messageId: "a", delta: "第一步" },
      { type: "REASONING_MESSAGE_END", messageId: "a" },
      { type: "TEXT_MESSAGE_START", messageId: "m1" },
      { type: "TEXT_MESSAGE_CONTENT", messageId: "m1", delta: "开始做" },
      { type: "REASONING_MESSAGE_START", messageId: "b" },
      { type: "REASONING_MESSAGE_CONTENT", messageId: "b", delta: "第二步" },
      { type: "REASONING_MESSAGE_END", messageId: "b" },
      { type: "RUN_FINISHED" },
    ]);

    expect(state.items.map((item) => item.kind)).toEqual(["thinking", "text", "thinking"]);
  });

  it("缺少 START 的增量也能兜住；REASONING_END 收掉所有还在流式的块", () => {
    const state = applyAll([
      { type: "RUN_STARTED", runId: "r1" },
      { type: "REASONING_MESSAGE_CONTENT", messageId: "x", delta: "直接来" },
      { type: "REASONING_END" },
    ]);
    const thinking = state.items.filter((item) => item.kind === "thinking");
    expect(thinking).toHaveLength(1);
    expect(thinking[0].kind === "thinking" && thinking[0].content).toBe("直接来");
    expect(thinking[0].kind === "thinking" && thinking[0].streaming).toBe(false);
  });

  it("断流时 RUN_FINISHED 也会收掉思考块，不会永远显示思考中", () => {
    const state = applyAll([
      { type: "RUN_STARTED", runId: "r1" },
      { type: "REASONING_MESSAGE_CONTENT", messageId: "x", delta: "想到一半" },
      // 没有 REASONING_END / MESSAGE_END，直接结束
      { type: "RUN_FINISHED" },
    ]);
    const thinking = state.items.find((item) => item.kind === "thinking");
    expect(thinking && thinking.kind === "thinking" && thinking.streaming).toBe(false);
  });
});

describe("replayMessages：已停止标记（Phase 12 / D25）", () => {
  it("aguiMeta.stopped=true 时助手气泡标注已停止", () => {
    const state = replayMessages([
      { role: "USER", content: "你好" },
      { role: "ASSISTANT", content: "写到一半", aguiMeta: { stopped: true } },
    ]);
    const assistant = state.items[1];
    expect(assistant.kind === "text" && assistant.stopped).toBe(true);
  });

  it("没有 stopped 标记时不标注", () => {
    const state = replayMessages([{ role: "ASSISTANT", content: "完整回答" }]);
    const assistant = state.items[0];
    expect(assistant.kind === "text" && assistant.stopped).toBeUndefined();
  });
});


/**
 * 回归（全量 e2e 暴露的重复气泡竞态）：
 * 同一轮的文字可能同时来自「实时 SSE 流」与「接续正在跑的 run」（切回会话/刷新后按 /run 轮询补文本），
 * 两者先后不定。若各建一条，界面上会出现**两个气泡装着同一轮的内容**。
 * 归并键是 runId —— 下面两种情况都必须只留一条。
 */
describe("reduceEvent：同 runId 只保留一条助手气泡（接续 vs 流）", () => {
  /** 模拟"接续"先建出的气泡：带本轮 runId，内容是已生成的部分 */
  const resumedState: ChatState = {
    items: [
      { kind: "text", id: "resumed-1", role: "assistant", content: "半截", streaming: true, runId: "r1" },
    ],
    status: "running",
    runId: "r1",
  };

  it("接续先建、流后来：复用同一条，不新建（历史 bug：会多一条气泡）", () => {
    const state = [
      { type: "TEXT_MESSAGE_START", messageId: "m1" } as AguiEventLike,
      { type: "TEXT_MESSAGE_CONTENT", messageId: "m1", delta: "文本" } as AguiEventLike,
    ].reduce(reduceEvent, resumedState);

    const assistants = state.items.filter((i) => i.kind === "text" && i.role === "assistant");
    expect(assistants, "同一轮只能有一条助手气泡").toHaveLength(1);
    // 复用后应当接着原来的内容继续追加（而不是丢掉已生成的部分）
    expect((assistants[0] as { content: string }).content).toBe("半截文本");
  });

  it("流先建、接续后来：CONTENT 没有 START 时也按 runId 并进同一条", () => {
    const state = [
      { type: "RUN_STARTED", runId: "r2" } as AguiEventLike,
      { type: "TEXT_MESSAGE_START", messageId: "m2" } as AguiEventLike,
      { type: "TEXT_MESSAGE_CONTENT", messageId: "m2", delta: "A" } as AguiEventLike,
      // 接续路径写进来的增量（没有 START、messageId 对不上）
      { type: "TEXT_MESSAGE_CONTENT", messageId: "other", delta: "B" } as AguiEventLike,
    ].reduce(reduceEvent, initialChatState);

    const assistants = state.items.filter((i) => i.kind === "text" && i.role === "assistant");
    expect(assistants, "同一轮只能有一条助手气泡").toHaveLength(1);
    expect((assistants[0] as { content: string }).content).toBe("AB");
  });

  it("不同轮次（runId 不同）仍应各自成条", () => {
    const state = [
      { type: "TEXT_MESSAGE_START", messageId: "m1" } as AguiEventLike,
      { type: "TEXT_MESSAGE_CONTENT", messageId: "m1", delta: "第一轮" } as AguiEventLike,
      { type: "RUN_FINISHED" } as AguiEventLike,
      { type: "RUN_STARTED", runId: "r2" } as AguiEventLike,
      { type: "TEXT_MESSAGE_START", messageId: "m2" } as AguiEventLike,
      { type: "TEXT_MESSAGE_CONTENT", messageId: "m2", delta: "第二轮" } as AguiEventLike,
    ].reduce(reduceEvent, initialChatState);

    const assistants = state.items.filter((i) => i.kind === "text" && i.role === "assistant");
    expect(assistants).toHaveLength(2);
  });
});
