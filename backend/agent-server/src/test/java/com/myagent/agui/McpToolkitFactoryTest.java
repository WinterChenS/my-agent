package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.common.BizException;
import org.junit.jupiter.api.Test;

/** 平台 transport → 官方 McpServerConfig.transport 的映射（Task 6.1）。 */
class McpToolkitFactoryTest {

    @Test
    void 平台传输类型映射到官方字面量() {
        assertThat(McpToolkitFactory.toOfficialTransport("STDIO")).isEqualTo("stdio");
        assertThat(McpToolkitFactory.toOfficialTransport("sse")).isEqualTo("sse");
        assertThat(McpToolkitFactory.toOfficialTransport("streamable_http")).isEqualTo("streamable-http");
    }

    @Test
    void 未知传输类型报业务错误() {
        assertThatThrownBy(() -> McpToolkitFactory.toOfficialTransport("GRPC"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("transport");
        assertThatThrownBy(() -> McpToolkitFactory.toOfficialTransport(null))
                .isInstanceOf(BizException.class);
    }
}
