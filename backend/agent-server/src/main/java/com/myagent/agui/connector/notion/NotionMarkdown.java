package com.myagent.agui.connector.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown ↔ Notion block 的转换（只做常用子集）。
 *
 * <p>为什么自己写而不是引库：Notion 的 block 结构是 JSON，库要么太重、要么转换质量不可控；
 * 我们只要「段落/标题/列表/待办/引用/代码/分割线 + 行内加粗斜体代码链接」这一层，
 * 让模型按 Markdown 思考、落到 Notion 里结构清楚就够了。
 * 表格、分栏、同步块等复杂块**不冒充**：降级成普通段落并原样保留文本。
 */
public final class NotionMarkdown {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 行内元素：**粗体** / *斜体* / `代码` / [文字](链接)。 */
    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[(.+?)]\\((.+?)\\)|(?<!\\*)\\*([^*\\n]+?)\\*(?!\\*)");

    private NotionMarkdown() {
    }

    // ===================== Markdown → blocks =====================

    /** 把一段 Markdown 转成 Notion children 数组。 */
    public static ArrayNode toBlocks(String markdown) {
        ArrayNode blocks = JSON.createArrayNode();
        if (markdown == null || markdown.isBlank()) {
            return blocks;
        }
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                index++;
                continue;
            }
            // 代码块
            if (trimmed.startsWith("```")) {
                String language = trimmed.substring(3).trim();
                StringBuilder code = new StringBuilder();
                index++;
                while (index < lines.length && !lines[index].trim().startsWith("```")) {
                    code.append(lines[index]).append('\n');
                    index++;
                }
                index++; // 跳过结束的 ```
                ObjectNode block = block("code");
                ObjectNode codeNode = block.putObject("code");
                ArrayNode codeText = codeNode.putArray("rich_text");
                codeText.addObject().put("type", "text").putObject("text")
                        .put("content", trimTrailingNewline(code.toString()));
                codeNode.put("language", language.isEmpty() ? "plain text" : language);
                blocks.add(block);
                continue;
            }
            // 分割线
            if (trimmed.matches("^([-*_])\\1{2,}$")) {
                blocks.add(block("divider"));
                index++;
                continue;
            }
            // 标题
            Matcher heading = Pattern.compile("^(#{1,3})\\s+(.*)$").matcher(trimmed);
            if (heading.matches()) {
                int level = heading.group(1).length();
                ObjectNode block = block("heading_" + level);
                block.withObject("/heading_" + level)
                        .set("rich_text", inline(heading.group(2).trim()));
                blocks.add(block);
                index++;
                continue;
            }
            // 待办
            Matcher todo = Pattern.compile("^[-*]\\s+\\[( |x|X)]\\s+(.*)$").matcher(trimmed);
            if (todo.matches()) {
                boolean checked = !" ".equals(todo.group(1));
                ObjectNode block = block("to_do");
                ObjectNode node = block.withObject("/to_do");
                node.put("checked", checked);
                node.set("rich_text", inline(todo.group(2).trim()));
                blocks.add(block);
                index++;
                continue;
            }
            // 无序列表
            Matcher bullet = Pattern.compile("^[-*+]\\s+(.*)$").matcher(trimmed);
            if (bullet.matches()) {
                ObjectNode block = block("bulleted_list_item");
                block.withObject("/bulleted_list_item").set("rich_text", inline(bullet.group(1).trim()));
                blocks.add(block);
                index++;
                continue;
            }
            // 有序列表
            Matcher numbered = Pattern.compile("^\\d+[.)]\\s+(.*)$").matcher(trimmed);
            if (numbered.matches()) {
                ObjectNode block = block("numbered_list_item");
                block.withObject("/numbered_list_item").set("rich_text", inline(numbered.group(1).trim()));
                blocks.add(block);
                index++;
                continue;
            }
            // 引用
            if (trimmed.startsWith(">")) {
                ObjectNode block = block("quote");
                block.withObject("/quote").set("rich_text",
                        inline(trimmed.substring(1).trim()));
                blocks.add(block);
                index++;
                continue;
            }
            // 普通段落（连续非空、非特殊行合并成一段）
            StringBuilder paragraph = new StringBuilder(trimmed);
            index++;
            while (index < lines.length) {
                String next = lines[index].trim();
                if (next.isEmpty() || isSpecial(next)) {
                    break;
                }
                paragraph.append('\n').append(next);
                index++;
            }
            ObjectNode block = block("paragraph");
            block.withObject("/paragraph").set("rich_text", inline(paragraph.toString()));
            blocks.add(block);
        }
        return blocks;
    }

    private static boolean isSpecial(String line) {
        return line.startsWith("#") || line.startsWith("```") || line.startsWith(">")
                || line.matches("^[-*+]\\s+.*") || line.matches("^\\d+[.)]\\s+.*")
                || line.matches("^([-*_])\\1{2,}$");
    }

    private static ObjectNode block(String type) {
        ObjectNode block = JSON.createObjectNode();
        block.put("object", "block");
        block.put("type", type);
        return block;
    }

    private static String trimTrailingNewline(String text) {
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    /** 行内 Markdown → Notion rich_text 数组。 */
    static ArrayNode inline(String text) {
        ArrayNode result = JSON.createArrayNode();
        if (text == null || text.isEmpty()) {
            return result;
        }
        Matcher matcher = INLINE.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                result.add(richText(text.substring(cursor, matcher.start()), null, null, null));
            }
            if (matcher.group(1) != null) {
                result.add(richText(matcher.group(1), true, null, null));
            } else if (matcher.group(2) != null) {
                result.add(richText(matcher.group(2), null, null, "code"));
            } else if (matcher.group(3) != null) {
                result.add(richText(matcher.group(3), null, matcher.group(4), null));
            } else if (matcher.group(5) != null) {
                result.add(richText(matcher.group(5), null, null, "italic"));
            }
            cursor = matcher.end();
        }
        if (cursor < text.length()) {
            result.add(richText(text.substring(cursor), null, null, null));
        }
        return result;
    }

    private static ObjectNode richText(String content, Boolean bold, String href, String style) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "text");
        ObjectNode text = node.putObject("text");
        text.put("content", content == null ? "" : content);
        if (href != null) {
            // Notion 要求 link 是对象 {url: ...}，写成字符串会被 API 判为非法
            text.putObject("link").put("url", href);
        }
        ObjectNode annotations = node.putObject("annotations");
        annotations.put("bold", Boolean.TRUE.equals(bold));
        annotations.put("italic", "italic".equals(style));
        annotations.put("code", "code".equals(style));
        return node;
    }

    // ===================== blocks → Markdown =====================

    /** 把 block 列表转成 Markdown（{@code depth} 用于嵌套缩进）。 */
    public static String toMarkdown(List<JsonNode> blocks) {
        StringBuilder out = new StringBuilder();
        appendBlocks(blocks, out, 0);
        return out.toString().stripTrailing();
    }

    private static void appendBlocks(List<JsonNode> blocks, StringBuilder out, int depth) {
        String indent = "    ".repeat(depth);
        for (JsonNode block : blocks) {
            String type = block.path("type").asText("");
            JsonNode body = block.path(type);
            switch (type) {
                case "heading_1" -> out.append("\n# ").append(plain(body)).append('\n');
                case "heading_2" -> out.append("\n## ").append(plain(body)).append('\n');
                case "heading_3" -> out.append("\n### ").append(plain(body)).append('\n');
                case "bulleted_list_item" -> out.append(indent).append("- ").append(plain(body)).append('\n');
                case "numbered_list_item" -> out.append(indent).append("1. ").append(plain(body)).append('\n');
                case "to_do" -> out.append(indent)
                        .append(body.path("checked").asBoolean(false) ? "- [x] " : "- [ ] ")
                        .append(plain(body)).append('\n');
                case "quote" -> out.append("> ").append(plain(body)).append('\n');
                case "code" -> out.append("```").append(body.path("language").asText("")).append('\n')
                        .append(plain(body)).append("\n```\n");
                case "divider" -> out.append("\n---\n");
                case "child_page" -> out.append(indent).append("- [子页面] ")
                        .append(block.path("child_page").path("title").asText("")).append('\n');
                case "image" -> out.append(indent).append("![图片](")
                        .append(block.path("image").path("external").path("url").asText(
                                block.path("image").path("file").path("url").asText("")))
                        .append(")\n");
                case "table_of_contents", "unsupported" -> out.append(indent).append("（不支持的块：")
                        .append(type).append("）\n");
                default -> {
                    String text = plain(body);
                    if (!text.isBlank()) {
                        out.append(indent).append(text).append('\n');
                    }
                }
            }
            // 子块递归
            if (block.path("has_children").asBoolean(false) && block.path("children").isArray()) {
                appendBlocks(toList(block.path("children")), out, depth + 1);
            }
        }
    }

    /** 取块里的纯文本（带行内样式的最简还原：粗体/斜体/代码/链接）。 */
    static String plain(JsonNode body) {
        JsonNode richText = body.path("rich_text");
        if (!richText.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : richText) {
            String content = item.path("plain_text").asText(item.path("text").path("content").asText(""));
            JsonNode annotations = item.path("annotations");
            String link = item.path("text").path("link").path("url").asText("");
            if (annotations.path("code").asBoolean(false)) {
                content = "`" + content + "`";
            }
            if (annotations.path("bold").asBoolean(false)) {
                content = "**" + content + "**";
            }
            if (annotations.path("italic").asBoolean(false)) {
                content = "*" + content + "*";
            }
            if (!link.isBlank()) {
                content = "[" + content + "](" + link + ")";
            }
            builder.append(content);
        }
        return builder.toString();
    }

    public static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(list::add);
        }
        return list;
    }
}
