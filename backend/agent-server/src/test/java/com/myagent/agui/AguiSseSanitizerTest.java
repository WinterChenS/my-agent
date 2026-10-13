package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** SSE 出站 JSON 去 null（Task 9.1 实测缺陷的回归测试）。 */
class AguiSseSanitizerTest {

    private final AguiSseSanitizer sanitizer = new AguiSseSanitizer(new ObjectMapper());

    @Test
    void 去掉对象里的null字段但保留有值字段() {
        String input = "{\"type\":\"RUN_FINISHED\",\"outcome\":{\"interrupts\":[{\"id\":\"x\",\"expiresAt\":null,\"reason\":\"tool_call\"}]}}";
        String out = sanitizer.stripNulls(input);
        assertThat(out).doesNotContain("expiresAt");
        assertThat(out).contains("\"reason\":\"tool_call\"");
        assertThat(out).contains("\"id\":\"x\"");
    }

    @Test
    void 数组元素里的null保留() {
        String out = sanitizer.stripNulls("{\"rows\":[null,1],\"a\":null}");
        assertThat(out).contains("[null,1]");
        assertThat(out).doesNotContain("\"a\"");
    }

    @Test
    void 非法JSON原样返回() {
        assertThat(sanitizer.stripNulls("not-json")).isEqualTo("not-json");
        assertThat(sanitizer.stripNulls(null)).isNull();
    }

    @org.junit.jupiter.api.Test
    void 厂商拒收图片时翻成可操作的中文() {
        // 实测：模型配置开了多模态但供应商根本不吃图，用户只看到一长串英文报错
        String raw = "{\"type\":\"RUN_ERROR\",\"message\":\"HTTP transport error ... {\\\"error\\\":"
                + "{\\\"message\\\":\\\"DeepSeek: .messages[1].image[0]: You have uploaded an unsupported image.\\\"}}\"}";
        String out = sanitizer.stripNulls(raw);
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("这个模型不接受图片"), out);
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("支持多模态（图片输入）"), out);
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("IMAGE_NOT_SUPPORTED"), out);
    }

    @org.junit.jupiter.api.Test
    void 上下文超长仍然翻成原来的建议() {
        String raw = "{\"type\":\"RUN_ERROR\",\"message\":\"context_length_exceeded\"}";
        String out = sanitizer.stripNulls(raw);
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("上下文超过模型窗口上限"), out);
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("CONTEXT_LENGTH_EXCEEDED"), out);
    }
}
