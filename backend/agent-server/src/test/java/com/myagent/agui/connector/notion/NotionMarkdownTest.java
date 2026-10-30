package com.myagent.agui.connector.notion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Markdown ↔ Notion block：模型按 Markdown 思考，落到 Notion 里结构要说得过去。
 *
 * <p>用 {@code BT} 常量拼反引号，避免源码里的三反引号被工具链吃掉。
 */
class NotionMarkdownTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 一个反引号 / 三个反引号（代码块围栏）。 */
    private static final String BT = "\u0060";
    private static final String FENCE = BT + BT + BT;

    @Test
    void markdown转块_覆盖常用结构() {
        String markdown = String.join("\n",
                "# 一级标题",
                "第一段正文，包含 **加粗**、`行内代码` 和 [链接](https://example.com)。",
                "",
                "## 二级标题",
                "- 列表项一",
                "- [ ] 待办未完成",
                "- [x] 待办已完成",
                "1. 有序一",
                "> 引用一句话",
                FENCE + "java",
                "System.out.println(\"hi\");",
                FENCE,
                "---");

        ArrayNode blocks = NotionMarkdown.toBlocks(markdown);
        List<String> types = new ArrayList<>();
        blocks.forEach(block -> types.add(block.path("type").asText()));
        assertThat(types).contains("heading_1", "paragraph", "heading_2",
                "bulleted_list_item", "to_do", "numbered_list_item", "quote", "code", "divider");

        JsonNode paragraph = first(blocks, "paragraph");
        assertThat(paragraph).isNotNull();
        assertThat(hasAnnotation(paragraph, "bold")).isTrue();
        assertThat(hasAnnotation(paragraph, "code")).isTrue();
        assertThat(hasLink(paragraph)).isTrue();
    }

    @Test
    void 待办的勾选状态要正确() {
        ArrayNode blocks = NotionMarkdown.toBlocks("- [x] 已完成\n- [ ] 没做");
        assertThat(blocks.get(0).path("to_do").path("checked").asBoolean()).isTrue();
        assertThat(blocks.get(1).path("to_do").path("checked").asBoolean()).isFalse();
    }

    @Test
    void 代码块保留语言与内容() {
        ArrayNode blocks = NotionMarkdown.toBlocks(FENCE + "python\nprint(1)\nprint(2)\n" + FENCE);
        JsonNode code = blocks.get(0).path("code");
        assertThat(code.path("language").asText()).isEqualTo("python");
        assertThat(code.path("rich_text").get(0).path("text").path("content").asText())
                .isEqualTo("print(1)\nprint(2)");
    }

    @Test
    void 空内容不产生块() {
        assertThat(NotionMarkdown.toBlocks(null)).isEmpty();
        assertThat(NotionMarkdown.toBlocks("   \n\n")).isEmpty();
    }

    @Test
    void 块转markdown_还原标题列表与样式() {
        ArrayNode blocks = JSON.createArrayNode();
        blocks.add(textBlock("heading_1", "标题"));
        blocks.add(textBlock("paragraph", "正文内容"));
        blocks.add(textBlock("bulleted_list_item", "条目"));
        ObjectNode todo = textBlock("to_do", "任务");
        ((ObjectNode) todo.path("to_do")).put("checked", true);
        blocks.add(todo);

        String markdown = NotionMarkdown.toMarkdown(NotionMarkdown.toList(blocks));

        assertThat(markdown).contains("# 标题").contains("正文内容").contains("- 条目").contains("- [x] 任务");
    }

    @Test
    void markdown来回转换不丢正文() {
        String markdown = String.join("\n", "# 周报", "本周做了三件事：", "- 上线连接器", "- 修了卡片冻结");
        String roundTrip = NotionMarkdown.toMarkdown(
                NotionMarkdown.toList(NotionMarkdown.toBlocks(markdown)));
        assertThat(roundTrip).contains("# 周报").contains("上线连接器").contains("修了卡片冻结");
    }

    // ===================== 辅助 =====================

    private static JsonNode first(ArrayNode blocks, String type) {
        for (JsonNode block : blocks) {
            if (type.equals(block.path("type").asText())) {
                return block;
            }
        }
        return null;
    }

    private static boolean hasAnnotation(JsonNode block, String key) {
        for (JsonNode item : block.path("paragraph").path("rich_text")) {
            if (item.path("annotations").path(key).asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLink(JsonNode block) {
        for (JsonNode item : block.path("paragraph").path("rich_text")) {
            if (!item.path("text").path("link").path("url").asText("").isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode textBlock(String type, String text) {
        ObjectNode block = JSON.createObjectNode();
        block.put("object", "block");
        block.put("type", type);
        ArrayNode rich = block.putObject(type).putArray("rich_text");
        rich.addObject().put("type", "text").putObject("text").put("content", text);
        return block;
    }
}
