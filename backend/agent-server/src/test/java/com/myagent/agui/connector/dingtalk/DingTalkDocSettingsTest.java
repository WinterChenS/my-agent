package com.myagent.agui.connector.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 钉钉文档：配置解析（链接→id、白名单）与「块 → Markdown」的宽容解析。
 *
 * <p>宽容解析是刻意设计的：钉钉不同文档类型的块结构并不统一，猜不全结构时
 * 宁可只丢样式，也不能"读不出内容"。
 */
class DingTalkDocSettingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, String> config() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("operatorId", "manager1234");
        return config;
    }

    @Test
    void 默认允许写入_块数300_白名单为空表示不限() {
        DingTalkDocSettings settings = DingTalkDocSettings.of(config());
        assertThat(settings.allowWrite()).isTrue();
        assertThat(settings.maxBlocks()).isEqualTo(300);
        assertThat(settings.allowsDoc("anything")).isTrue();
        assertThat(settings.allowsWorkspace("anything")).isTrue();
    }

    @Test
    void 关闭写入与自定义块数() {
        Map<String, String> config = config();
        config.put("allowWrite", "false");
        config.put("maxBlocks", "50");
        DingTalkDocSettings settings = DingTalkDocSettings.of(config);
        assertThat(settings.allowWrite()).isFalse();
        assertThat(settings.maxBlocks()).isEqualTo(50);
    }

    @Test
    void 文档链接能提取出id() {
        assertThat(DingTalkDocSettings.Ids.normalize(
                "https://alidocs.dingtalk.com/i/nodes/abcdEFGH12345678?utm_scene=team_space"))
                .isEqualTo("abcdEFGH12345678");
        assertThat(DingTalkDocSettings.Ids.normalize("abcdEFGH12345678")).isEqualTo("abcdEFGH12345678");
        assertThat(DingTalkDocSettings.Ids.normalize("  ")).isNull();
    }

    @Test
    void 白名单支持粘链接() {
        Map<String, String> config = config();
        config.put("allowedDocKeys", "https://alidocs.dingtalk.com/i/nodes/abcdEFGH12345678, zzzzYYYY99998888");
        DingTalkDocSettings settings = DingTalkDocSettings.of(config);

        assertThat(settings.allowsDoc("abcdEFGH12345678")).isTrue();
        assertThat(settings.allowsDoc("zzzzYYYY99998888")).isTrue();
        assertThat(settings.allowsDoc("otherDocKey123456")).isFalse();
    }

    @Test
    void 知识库白名单独立判定() {
        Map<String, String> config = config();
        config.put("allowedWorkspaceIds", "ws-1111");
        DingTalkDocSettings settings = DingTalkDocSettings.of(config);

        assertThat(settings.allowsWorkspace("ws-1111")).isTrue();
        assertThat(settings.allowsWorkspace("ws-2222")).isFalse();
        // 文档白名单为空 → 文档不限
        assertThat(settings.allowsDoc("whatever")).isTrue();
    }

    // ===================== 块 → Markdown =====================

    @Test
    void 常见块类型转成markdown() {
        ObjectNode response = JSON.createObjectNode();
        ArrayNode data = response.putObject("result").putArray("data");
        data.add(block("heading", "标题一"));
        data.add(block("paragraph", "一段正文"));
        data.add(block("bulletedList", "条目一"));
        data.add(todoBlock("任务", true));
        data.add(block("quote", "引用"));
        data.add(block("code", "print(1)"));

        String markdown = DingTalkDocMarkdown.toMarkdown(response);

        assertThat(markdown).contains("# 标题一", "一段正文", "- 条目一", "- [x] 任务", "> 引用", "print(1)");
    }

    @Test
    void 结构不认识的块也要把文字读出来() {
        ObjectNode response = JSON.createObjectNode();
        ArrayNode data = response.putObject("result").putArray("data");
        // 只有 generic 结构：没有 blockType，文字藏在 textRun 里
        ObjectNode weird = data.addObject();
        ObjectNode paragraph = weird.putObject("someUnknownContainer");
        paragraph.putArray("elements").addObject().putObject("textRun").put("content", "这段文字不能丢");

        String markdown = DingTalkDocMarkdown.toMarkdown(response);

        assertThat(markdown).contains("这段文字不能丢");
    }

    @Test
    void 嵌套children不重复输出文字() {
        ObjectNode response = JSON.createObjectNode();
        ArrayNode data = response.putObject("result").putArray("data");
        ObjectNode parent = block("paragraph", "父级文字");
        ArrayNode children = parent.putArray("children");
        children.add(block("paragraph", "子级文字"));
        data.add(parent);

        String markdown = DingTalkDocMarkdown.toMarkdown(response);

        assertThat(markdown.split("父级文字", -1)).hasSize(2); // 只出现一次
        assertThat(markdown).contains("子级文字");
    }

    @Test
    void 空响应返回空字符串() {
        assertThat(DingTalkDocMarkdown.toMarkdown(JSON.createObjectNode())).isEmpty();
    }

    private static ObjectNode block(String type, String text) {
        ObjectNode block = JSON.createObjectNode();
        block.put("blockType", type);
        ObjectNode body = block.putObject(type);
        body.putArray("elements").addObject().putObject("textRun").put("content", text);
        return block;
    }

    private static ObjectNode todoBlock(String text, boolean checked) {
        ObjectNode block = block("todo", text);
        block.put("checked", checked);
        return block;
    }
}
