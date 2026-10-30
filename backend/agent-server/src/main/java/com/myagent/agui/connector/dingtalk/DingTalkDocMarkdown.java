package com.myagent.agui.connector.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 钉钉文档块 → Markdown。
 *
 * <p>钉钉的块模型和 Notion 完全不同（`{blockType, paragraph:{elements:[{textRun:{content}}]}}` 一类），
 * 而且不同文档类型（文档/表格/多维表）返回结构还会不一样。这里的策略是**宽容**：
 * <ul>
 *   <li>能认出类型的（标题/列表/待办/引用/代码/分割线）按 Markdown 输出；</li>
 *   <li>认不出的，就把它里面的文字（递归找 {@code content}/{@code text}）当普通段落输出，
 *       宁可样式丢一点，也不能"读不出内容"。</li>
 * </ul>
 * 写回时用 {@code overwriteContent(dataType=markdown)}，不走块结构 —— 这样不会因为块 id/结构猜错而写坏文档。
 */
public final class DingTalkDocMarkdown {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DingTalkDocMarkdown() {
    }

    /** 把 blocks 接口返回的 JSON（{@code result.data} 里的数组）转成 Markdown。 */
    public static String toMarkdown(JsonNode response) {
        JsonNode data = response.path("result").path("data");
        if (!data.isArray()) {
            // 有些版本直接返回 data
            data = response.path("data");
        }
        if (!data.isArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode block : data) {
            appendBlock(block, out, 0);
        }
        return out.toString().stripTrailing();
    }

    private static void appendBlock(JsonNode block, StringBuilder out, int depth) {
        String type = typeOf(block);
        String text = textOf(block).strip();
        String indent = "    ".repeat(depth);
        if (text.isEmpty() && !type.contains("divider")) {
            // 可能是容器块：把 children 递归出来
            JsonNode children = block.path("children");
            if (children.isArray()) {
                for (JsonNode child : children) {
                    appendBlock(child, out, depth + 1);
                }
            }
            return;
        }
        switch (type) {
            case "heading", "heading1", "title" -> out.append("\n# ").append(text).append('\n');
            case "heading2", "subtitle" -> out.append("\n## ").append(text).append('\n');
            case "heading3" -> out.append("\n### ").append(text).append('\n');
            case "bulletedlist", "bulletedlistitem", "unorderedlist", "bullet" ->
                    out.append(indent).append("- ").append(text).append('\n');
            case "orderedlist", "orderedlistitem", "numberlist" ->
                    out.append(indent).append("1. ").append(text).append('\n');
            case "todo", "todolist", "checklist" -> {
                boolean checked = block.path("checked").asBoolean(false)
                        || block.path("properties").path("checked").asBoolean(false);
                out.append(indent).append(checked ? "- [x] " : "- [ ] ").append(text).append('\n');
            }
            case "quote", "blockquote" -> out.append("> ").append(text).append('\n');
            case "code", "codeblock" -> out.append("```\n").append(text).append("\n```\n");
            case "divider", "horizontalrule" -> out.append("\n---\n");
            case "image", "img" -> out.append("![图片](")
                    .append(block.path("url").asText(block.path("src").asText(""))).append(")\n");
            default -> out.append(indent).append(text).append('\n');
        }
        JsonNode children = block.path("children");
        if (children.isArray()) {
            for (JsonNode child : children) {
                appendBlock(child, out, depth + 1);
            }
        }
    }

    /** 块类型：兼容 blockType / type / elementType 三种写法，统一小写。 */
    static String typeOf(JsonNode block) {
        for (String key : List.of("blockType", "type", "elementType")) {
            String value = block.path(key).asText("");
            if (!value.isBlank()) {
                return value.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    /** 递归收集文字：content / text / textRun.content 都认。 */
    static String textOf(JsonNode node) {
        List<String> parts = new ArrayList<>();
        collectText(node, parts, 0);
        return String.join("", parts);
    }

    private static void collectText(JsonNode node, List<String> parts, int depth) {
        if (node == null || depth > 12) {
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectText(item, parts, depth + 1));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        for (String key : List.of("content", "text")) {
            JsonNode value = node.path(key);
            if (value.isTextual() && !value.asText().isEmpty()) {
                parts.add(value.asText());
                return;
            }
        }
        JsonNode textRun = node.path("textRun").path("content");
        if (textRun.isTextual() && !textRun.asText().isEmpty()) {
            parts.add(textRun.asText());
            return;
        }
        node.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            // children 由 appendBlock 单独递归，这里跳过，否则同一段文字会被输出两次
            if ("blockType".equals(name) || "type".equals(name) || "elementType".equals(name)
                    || "children".equals(name)) {
                return;
            }
            collectText(entry.getValue(), parts, depth + 1);
        });
    }
}
