package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agui.event.AguiEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事件折叠（回放）的单测。
 *
 * <p>守的是"刷新/打开历史会话后，思考/工具调用/子 Agent 还在、顺序还对"这件事，
 * 因为落库的就是这里折叠出来的 {@code agui_meta.replay}。
 */
class ReplayRecorderTest {

    private static AguiEvent text(String delta) {
        return new AguiEvent.TextMessageContent("t1", "r1", "m1", delta);
    }

    private static AguiEvent reasoning(String delta) {
        return new AguiEvent.ReasoningMessageContent("t1", "r1", "r-m1", delta);
    }

    private static AguiEvent toolStart(String id, String name) {
        return new AguiEvent.ToolCallStart("t1", "r1", id, name);
    }

    private static AguiEvent toolEnd(String id) {
        return new AguiEvent.ToolCallEnd("t1", "r1", id);
    }

    private static AguiEvent sub(String name, Map<String, Object> payload) {
        return new AguiEvent.Custom("t1", "r1", name, payload);
    }

    @Test
    @DisplayName("连续 delta 合并成一段，思考/正文/工具按到达顺序排列")
    void foldsInArrivalOrder() {
        ReplayRecorder recorder = new ReplayRecorder();
        recorder.accept(reasoning("先想"));
        recorder.accept(reasoning("一下"));
        recorder.accept(toolStart("c1", "browser_open"));
        recorder.accept(toolEnd("c1"));
        recorder.accept(text("打开"));
        recorder.accept(text("了"));

        List<Map<String, Object>> steps = recorder.steps();
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0)).containsEntry("kind", "thinking").containsEntry("text", "先想一下");
        assertThat(steps.get(1)).containsEntry("kind", "tool").containsEntry("name", "browser_open")
                .containsEntry("done", true);
        assertThat(steps.get(2)).containsEntry("kind", "text").containsEntry("text", "打开了");
    }

    @Test
    @DisplayName("交互卡片也进 replay（否则刷新/接续后卡片直接消失）")
    void keepsInteractiveCards() {
        ReplayRecorder recorder = new ReplayRecorder();
        recorder.accept(new AguiEvent.Custom("t1", "r1", "confirm_card",
                Map.of("title", "需要你的选择", "message", "用哪种模板？",
                        "options", List.of(Map.of("label", "月度", "value", "月度")))));
        recorder.accept(new AguiEvent.Custom("t1", "r1", "token_usage", Map.of("total", 1)));

        List<Map<String, Object>> steps = recorder.steps();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("kind", "card").containsEntry("name", "confirm_card");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) steps.get(0).get("data");
        assertThat(data).containsEntry("message", "用哪种模板？");
    }

    @Test
    @DisplayName("没有任何内容时返回 null（不写无意义的 replay 字段）")
    void emptyReturnsNull() {
        assertThat(new ReplayRecorder().steps()).isNull();
    }

    @Test
    @DisplayName("子 Agent：按 source 归并，内部思考/工具/正文也保持顺序，AGENT_END 置 done")
    void foldsSubagent() {
        ReplayRecorder recorder = new ReplayRecorder();
        recorder.accept(sub("subagent.lifecycle",
                Map.of("source", "t1/gp", "type", "AGENT_START", "name", "general-purpose")));
        recorder.accept(sub("subagent.thinking", Map.of("source", "t1/gp", "delta", "想")));
        recorder.accept(sub("subagent.tool_call", Map.of("source", "t1/gp", "type", "TOOL_CALL_START",
                "toolName", "read_file")));
        recorder.accept(sub("subagent.tool_call", Map.of("source", "t1/gp", "type", "TOOL_CALL_END")));
        recorder.accept(sub("subagent.text", Map.of("source", "t1/gp", "delta", "读到了")));
        recorder.accept(sub("subagent.lifecycle", Map.of("source", "t1/gp", "type", "AGENT_END")));

        List<Map<String, Object>> steps = recorder.steps();
        assertThat(steps).hasSize(1);
        Map<String, Object> entry = steps.get(0);
        assertThat(entry).containsEntry("kind", "subagent").containsEntry("status", "done")
                .containsEntry("name", "general-purpose");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inner = (List<Map<String, Object>>) entry.get("steps");
        assertThat(inner).hasSize(3);
        assertThat(inner.get(0)).containsEntry("kind", "thinking").containsEntry("text", "想");
        assertThat(inner.get(1)).containsEntry("kind", "tool").containsEntry("name", "read_file")
                .containsEntry("done", true);
        assertThat(inner.get(2)).containsEntry("kind", "text").containsEntry("text", "读到了");
    }

    @Test
    @DisplayName("两个子 Agent 各自独立，互不覆盖")
    void keepsTwoSubagentsSeparate() {
        ReplayRecorder recorder = new ReplayRecorder();
        recorder.accept(sub("subagent.text", Map.of("source", "t1/alpha", "delta", "A")));
        recorder.accept(sub("subagent.text", Map.of("source", "t1/beta", "delta", "B")));
        recorder.accept(sub("subagent.text", Map.of("source", "t1/alpha", "delta", "2")));

        List<Map<String, Object>> steps = recorder.steps();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0)).containsEntry("source", "t1/alpha");
        assertThat(steps.get(1)).containsEntry("source", "t1/beta");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alpha = (List<Map<String, Object>>) steps.get(0).get("steps");
        assertThat(alpha.get(0)).containsEntry("text", "A2");
    }
}
