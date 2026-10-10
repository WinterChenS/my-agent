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
}
