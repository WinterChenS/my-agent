package com.myagent.agui;

import io.agentscope.core.agui.event.AguiEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一轮 run 的 AG-UI 事件折叠成**可回放的步骤序列**（存进 assistant 消息的 {@code agui_meta.replay}）。
 *
 * <p>为什么需要：数据库里只存了最终正文，刷新/打开历史会话时思考、工具调用、子 Agent
 * 全都消失了（用户实测反馈）。这里把事件按**到达顺序**折叠成紧凑的步骤：
 *
 * <pre>
 * {"kind":"thinking","text":"..."}      // 连续 delta 合并成一段
 * {"kind":"text","text":"..."}
 * {"kind":"tool","name":"browser_open","done":true}
 * {"kind":"subagent","source":"t/gp","name":"gp","status":"done","steps":[...]}
 * </pre>
 *
 * <p>刻意**不存每个 delta**：一次回答可能有上万条 TEXT_MESSAGE_CONTENT，逐条落库既费空间也没意义。
 */
final class ReplayRecorder {

    private final List<Map<String, Object>> steps = new ArrayList<>();
    /** 子 Agent 的条目（按 source 归并，内部同样用 steps 结构） */
    private final Map<String, Map<String, Object>> subagents = new LinkedHashMap<>();

    void accept(AguiEvent event) {
        if (event instanceof AguiEvent.TextMessageContent content) {
            appendText("text", content.delta());
        } else if (event instanceof AguiEvent.ReasoningMessageContent reasoning) {
            appendText("thinking", reasoning.delta());
        } else if (event instanceof AguiEvent.ReasoningMessageChunk chunk) {
            appendText("thinking", chunk.delta());
        } else if (event instanceof AguiEvent.ToolCallStart start) {
            addTool(start.toolCallName());
        } else if (event instanceof AguiEvent.ToolCallEnd) {
            markLastToolDone(steps);
        } else if (event instanceof AguiEvent.ToolCallResult) {
            markLastToolDone(steps);
        } else if (event instanceof AguiEvent.Custom custom) {
            acceptCustom(custom);
        }
    }

    /** 折叠后的步骤（JSON 友好，直接塞进 agui_meta）。空列表返回 null，避免无意义字段。 */
    List<Map<String, Object>> steps() {
        return steps.isEmpty() ? null : steps;
    }

    /**
     * 深拷贝快照：给「运行中的接续」用。
     *
     * <p>必须拷贝 —— 运行中步骤还在被追加/合并，直接把手里的可变结构交给别的线程会读到半截状态。
     */
    List<Map<String, Object>> snapshot() {
        if (steps.isEmpty()) {
            return java.util.List.of();
        }
        List<Map<String, Object>> copy = new ArrayList<>(steps.size());
        for (Map<String, Object> step : steps) {
            copy.add(deepCopy(step));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> step) {
        Map<String, Object> result = new LinkedHashMap<>();
        step.forEach((key, value) -> {
            if (value instanceof List<?> list) {
                List<Object> items = new ArrayList<>(list.size());
                for (Object item : list) {
                    items.add(item instanceof Map<?, ?> map ? deepCopy((Map<String, Object>) map) : item);
                }
                result.put(key, items);
            } else {
                result.put(key, value);
            }
        });
        return result;
    }

    // ---------------- 主 Agent ----------------

    private void appendText(String kind, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        Map<String, Object> last = steps.isEmpty() ? null : steps.get(steps.size() - 1);
        if (last != null && kind.equals(last.get("kind"))) {
            last.put("text", String.valueOf(last.get("text")) + delta);
            return;
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("kind", kind);
        step.put("text", delta);
        steps.add(step);
    }

    private void addTool(String name) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("kind", "tool");
        step.put("name", name == null || name.isBlank() ? "tool" : name);
        step.put("done", false);
        steps.add(step);
    }

    private static void markLastToolDone(List<Map<String, Object>> list) {
        for (int i = list.size() - 1; i >= 0; i -= 1) {
            Map<String, Object> step = list.get(i);
            if ("tool".equals(step.get("kind")) && Boolean.FALSE.equals(step.get("done"))) {
                step.put("done", true);
                return;
            }
        }
    }

    // ---------------- 子 Agent ----------------

    @SuppressWarnings("unchecked")
    private void acceptCustom(AguiEvent.Custom custom) {
        String name = custom.name();
        if (name == null || !name.startsWith("subagent.")) {
            return;
        }
        Map<String, Object> payload = asMap(custom.value());
        if (payload == null) {
            return;
        }
        String source = asString(payload.get("source"));
        if (source == null || source.isBlank()) {
            source = "subagent";
        }
        Map<String, Object> entry = subagents.get(source);
        if (entry == null) {
            entry = new LinkedHashMap<>();
            entry.put("kind", "subagent");
            entry.put("source", source);
            entry.put("name", source.contains("/") ? source.substring(source.lastIndexOf('/') + 1) : source);
            entry.put("status", "running");
            entry.put("steps", new ArrayList<Map<String, Object>>());
            subagents.put(source, entry);
            steps.add(entry);
        }
        List<Map<String, Object>> inner = (List<Map<String, Object>>) entry.get("steps");
        String type = asString(payload.get("type"));
        switch (name) {
            case "subagent.lifecycle" -> {
                String label = asString(payload.get("name"));
                if (label != null && !label.isBlank()) {
                    entry.put("name", label);
                }
                if ("AGENT_END".equals(type)) {
                    entry.put("status", "done");
                } else if ("AGENT_START".equals(type)) {
                    entry.put("status", "running");
                }
            }
            case "subagent.text" -> appendInner(inner, "text", asString(payload.get("delta")));
            case "subagent.thinking" -> appendInner(inner, "thinking", asString(payload.get("delta")));
            case "subagent.tool_call" -> {
                if ("TOOL_CALL_END".equals(type)) {
                    markLastToolDone(inner);
                } else {
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("kind", "tool");
                    step.put("name", asString(payload.get("toolName")) == null
                            ? "tool" : asString(payload.get("toolName")));
                    step.put("done", false);
                    inner.add(step);
                }
            }
            case "subagent.tool_result" -> markLastToolDone(inner);
            case "subagent.require_confirm" -> entry.put("confirm", asString(payload.get("message")));
            default -> {
                // 未知子事件忽略
            }
        }
    }

    private static void appendInner(List<Map<String, Object>> inner, String kind, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        Map<String, Object> last = inner.isEmpty() ? null : inner.get(inner.size() - 1);
        if (last != null && kind.equals(last.get("kind"))) {
            last.put("text", String.valueOf(last.get("text")) + delta);
            return;
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("kind", kind);
        step.put("text", delta);
        inner.add(step);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        if (value instanceof String text && text.startsWith("{")) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(text, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
