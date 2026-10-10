package com.myagent.agui;

import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * demo-agent 的内置演示工具：演示如何从工具里主动发出 CUSTOM 事件（确认卡 / 映射卡）。
 *
 * <p>机制：AgentScope 的 {@link CustomEvent} 会被官方 {@code CustomAgentEventConverter}
 * 映射为 AG-UI 的 CUSTOM 事件；工具通过 Reactor 上下文里的 {@link AgentEventEmitter} 发出。
 *
 * <p>data 结构严格遵守技术方案第 6 节的冻结契约（ConfirmCardData / MappingCardData）。
 */
public final class DemoCardTools {

    private DemoCardTools() {}

    /** 两个演示工具：发确认卡、发映射卡。 */
    public static List<AgentTool> tools() {
        return List.of(new ConfirmCardTool(), new MappingCardTool());
    }

    /** 发确认卡，等待用户选择（选项由前端回传）。 */
    static final class ConfirmCardTool implements AgentTool {

        @Override
        public String getName() {
            return "demo_confirm_card";
        }

        @Override
        public String getDescription() {
            return "向用户展示一张确认卡（含若干选项），用于请求用户确认后再继续。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> question = new LinkedHashMap<>();
            question.put("type", "string");
            question.put("description", "要向用户确认的问题");
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("question", question);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", List.of("question"));
            return schema;
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            String question = String.valueOf(param.getInput().getOrDefault("question", "是否继续？"));
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("title", "需要你的确认");
            value.put("message", question);
            value.put("options", List.of(
                    Map.of("label", "继续", "value", "continue"),
                    Map.of("label", "取消", "value", "cancel")));
            value.put("requestId", UUID.randomUUID().toString());

            return Mono.deferContextual(context -> {
                AgentEventEmitter.fromContext(context)
                        .ifPresent(emitter -> emitter.emit(new CustomEvent("confirm_card", value)));
                return Mono.just(result(param, "已向用户展示确认卡，等待用户选择。"));
            });
        }
    }

    /** 发映射卡：以表格形式展示结构化数据。 */
    static final class MappingCardTool implements AgentTool {

        @Override
        public String getName() {
            return "demo_mapping_card";
        }

        @Override
        public String getDescription() {
            return "以表格（映射卡）形式向用户展示结构化数据。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> topic = new LinkedHashMap<>();
            topic.put("type", "string");
            topic.put("description", "要展示的主题");
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("topic", topic);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", List.of("topic"));
            return schema;
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            String topic = String.valueOf(param.getInput().getOrDefault("topic", "示例主题"));

            List<Map<String, Object>> columns = new ArrayList<>();
            columns.add(Map.of("key", "field", "label", "字段"));
            columns.add(Map.of("key", "source", "label", "来源"));
            columns.add(Map.of("key", "target", "label", "目标"));

            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(Map.of("field", topic + "-名称", "source", "A 系统", "target", "name"));
            rows.add(Map.of("field", topic + "-编号", "source", "A 系统", "target", "code"));

            Map<String, Object> value = new LinkedHashMap<>();
            value.put("title", "字段映射：" + topic);
            value.put("columns", columns);
            value.put("rows", rows);

            return Mono.deferContextual(context -> {
                AgentEventEmitter.fromContext(context)
                        .ifPresent(emitter -> emitter.emit(new CustomEvent("mapping_card", value)));
                return Mono.just(result(param, "已向用户展示映射卡。"));
            });
        }
    }

    private static ToolResultBlock result(ToolCallParam param, String text) {
        return new ToolResultBlock(
                param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId(),
                param.getToolUseBlock() == null ? null : param.getToolUseBlock().getName(),
                List.of(TextBlock.builder().text(text).build()),
                Map.of());
    }
}
