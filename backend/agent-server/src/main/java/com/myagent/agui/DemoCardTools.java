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

    /**
     * 两个交互工具：让用户做选择（{@code ask_user_choice}）、展示字段映射（{@code propose_mapping}）。
     *
     * <p>名字不带 demo 前缀 —— 它们是正式能力（不是演示），模型看到的工具名要和"能干什么"一致。
     */
    public static List<AgentTool> tools() {
        return List.of(new ConfirmCardTool(), new MappingCardTool());
    }

    /** 发确认卡，等待用户选择（选项由前端回传）。 */
    static final class ConfirmCardTool implements AgentTool {

        @Override
        public String getName() {
            return "ask_user_choice";
        }

        @Override
        public String getDescription() {
            // 描述要写清「什么时候用」+「选项要给具体候选」——否则模型只会问一句、卡片只剩固定的继续/取消
            // （用户实测反馈：每次弹卡都只有「继续 / 取消」，没法选语气/方案）
            return "向用户展示一张**选择卡**：把你能想到的候选答案作为按钮交给他点，而不是让他在正文里打字。"
                    + "用户点哪个，那个选项的文字会作为后续消息回到你这里。"
                    + "什么时候必须用它："
                    + "① 不可逆或有风险的操作前确认（删除、覆盖、发布、扣费、改生产数据）；"
                    + "② 有多个方案/风格/取舍需要用户拍板；"
                    + "③ 缺少只有用户知道的信息，且答案可以枚举。"
                    + "**options 要给真正可选的候选**，让用户一眼就能点选，例如："
                    + "改写语气 → [\"更正式\", \"更亲切\", \"更简短\"]；"
                    + "发信前确认 → [\"就这样发送\", \"改得更客气\", \"加一句结尾问候\"]；"
                    + "选方案 → [\"方案A：xxx\", \"方案B：yyy\"]。"
                    + "不要只问一句然后指望固定的「继续/取消」：那样用户只能回答是/否，选不了具体做法。"
                    + "只有确实是「干/不干」二选一时才省略 options（默认给 继续/取消）。"
                    + "question 要写清**后果或取舍**；allowCustom=true 时卡片上还会有输入框，让用户写你自己的选项之外的答案。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("question", Map.of(
                    "type", "string",
                    "description", "要向用户确认/征询的问题，写清后果或取舍"));
            properties.put("options", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "description", "候选答案（2-8 个短句，用户直接点选）。"
                            + "例：[\"更正式\", \"更亲切\", \"更简短\"]；确实只有干/不干时可以不传"));
            properties.put("title", Map.of(
                    "type", "string",
                    "description", "卡片标题，默认「需要你的确认」"));
            properties.put("allowCustom", Map.of(
                    "type", "boolean",
                    "description", "是否允许用户自己输入候选之外的答案（默认 false）"));
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
            List<Map<String, Object>> options = toOptions(param.getInput().get("options"));
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("title", orDefault(param.getInput().get("title"), "需要你的选择"));
            value.put("message", question);
            value.put("options", options);
            value.put("allowCustom", Boolean.TRUE.equals(param.getInput().get("allowCustom")));
            value.put("requestId", UUID.randomUUID().toString());

            return Mono.deferContextual(context -> {
                AgentEventEmitter.fromContext(context)
                        .ifPresent(emitter -> emitter.emit(new CustomEvent("confirm_card", value)));
                return Mono.just(result(param, "已向用户展示选择卡（选项：" + options.stream()
                        .map(option -> String.valueOf(option.get("label")))
                        .toList() + "）。等用户点选后会作为后续消息回到你这里。"));
            });
        }

        /**
         * 把模型给的候选答案转成卡片的 options。
         *
         * <p>约定：{@code label} 就是回传给模型的文字（所以两者相同），
         * 这样用户点「更亲切」时模型收到的就是「更亲切」而不是英文字面值。
         * 没给候选（或给空）时退化成「继续/取消」——这是"干/不干"二选一的默认形态。
         */
        static List<Map<String, Object>> toOptions(Object raw) {
            List<String> labels = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item == null) {
                        continue;
                    }
                    String label;
                    if (item instanceof Map<?, ?> map) {
                        Object raw2 = map.get("label") != null ? map.get("label") : map.get("value");
                        label = raw2 == null ? "" : String.valueOf(raw2);
                    } else {
                        label = String.valueOf(item);
                    }
                    label = label == null ? "" : label.trim();
                    if (!label.isEmpty()) {
                        labels.add(label);
                    }
                }
            }
            if (labels.isEmpty()) {
                return List.of(
                        Map.of("label", "继续", "value", "继续"),
                        Map.of("label", "取消", "value", "取消"));
            }
            // 卡片上放太多按钮会挤成一坨；超过 8 个就截断（模型应该给 2-5 个）
            return labels.stream().limit(8)
                    .map(label -> Map.<String, Object>of("label", label, "value", label))
                    .toList();
        }

        private static String orDefault(Object value, String fallback) {
            String text = value == null ? "" : String.valueOf(value).trim();
            return text.isEmpty() ? fallback : text;
        }
    }

    /** 发映射卡：以表格形式展示结构化数据。 */
    static final class MappingCardTool implements AgentTool {

        @Override
        public String getName() {
            return "propose_mapping";
        }

        @Override
        public String getDescription() {
            return "以**映射卡**（表格）形式向用户展示结构化数据，比在正文里堆文字清楚得多。"
                    + "适合：字段对应关系、多行对照清单、配置项列表这类「一行一条、有多个列」的内容。"
                    + "参数 topic 是要展示的主题。";
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
