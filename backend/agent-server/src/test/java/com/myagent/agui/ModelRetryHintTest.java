package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModelRetryHintTest {

    @Test
    void 流中断的报错会说明已重试过() {
        String message = "OpenAI API error in streaming response: deepseek-日抛:"
                + " connection lost mid-reply (unexpected EOF)";
        String decorated = new ModelRetryHint(3).decorate(message);

        assertThat(decorated).startsWith(message);
        assertThat(decorated).contains("已按配置自动重试 3 次");
    }

    @Test
    void 参数错误的报错不加说明_因为本来就不重试() {
        String message = "HTTP transport error during streaming: HTTP request failed with status 400"
                + " | {\"error\": {\"message\": \"invalid request: messages must not be empty\"}}";
        assertThat(new ModelRetryHint(3).decorate(message)).isEqualTo(message);
    }

    @Test
    void 重试关闭时不加说明() {
        String message = "connection lost mid-reply";
        assertThat(new ModelRetryHint(0).decorate(message)).isEqualTo(message);
    }

    @Test
    void 重试用尽时不留_Retries_exhausted_这种内部话术() {
        String decorated = new ModelRetryHint(3).decorate("Retries exhausted: 3/3");

        assertThat(decorated).doesNotContain("Retries exhausted");
        assertThat(decorated).contains("已按配置自动重试 3 次");
        assertThat(decorated).contains("换一个模型");
    }

    @Test
    void 空信息不会崩() {
        assertThat(new ModelRetryHint(3).decorate(null)).isNull();
        assertThat(new ModelRetryHint(3).decorate("")).isEmpty();
    }
}
