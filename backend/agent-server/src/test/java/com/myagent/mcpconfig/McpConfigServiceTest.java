package com.myagent.mcpconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.common.BizException;
import com.myagent.mcpconfig.dto.McpServerRequest;
import com.myagent.mcpconfig.dto.McpServerResponse;
import com.myagent.mcpconfig.service.McpConfigService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** MCP Server CRUD 与校验（连接专用测试库，事务回滚）。不在此处做真实连接（由 verify-p6-mcp.sh 覆盖）。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class McpConfigServiceTest {

    @Autowired
    private McpConfigService mcpConfigService;

    @Test
    void 创建STDIO并回读字段() {
        McpServerResponse created = mcpConfigService.create(new McpServerRequest(
                "mcp-stdio-" + System.nanoTime(),
                "STDIO",
                null,
                "npx",
                List.of("-y", "@modelcontextprotocol/server-everything", "stdio"),
                Map.of("FOO", "bar"),
                Map.of(),
                "ENABLED"));

        assertThat(created.id()).isNotNull();
        assertThat(created.transport()).isEqualTo("STDIO");
        assertThat(created.args()).containsExactly("-y", "@modelcontextprotocol/server-everything", "stdio");
        assertThat(created.env()).containsEntry("FOO", "bar");
        assertThat(created.status()).isEqualTo("ENABLED");
    }

    @Test
    void STDIO缺少command报业务错误() {
        assertThatThrownBy(() -> mcpConfigService.create(new McpServerRequest(
                        "mcp-nocmd-" + System.nanoTime(), "STDIO", null, null, null, null, null, "ENABLED")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("command");
    }

    @Test
    void HTTP传输缺少endpointUrl报业务错误() {
        assertThatThrownBy(() -> mcpConfigService.create(new McpServerRequest(
                        "mcp-nourl-" + System.nanoTime(), "STREAMABLE_HTTP", null, null, null, null, null, "ENABLED")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("endpointUrl");
    }

    @Test
    void 非法transport报业务错误() {
        assertThatThrownBy(() -> mcpConfigService.create(new McpServerRequest(
                        "mcp-bad-" + System.nanoTime(), "GRPC", "http://x", null, null, null, null, "ENABLED")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("transport");
    }

    @Test
    void 名称重复报业务错误() {
        String name = "mcp-dup-" + System.nanoTime();
        mcpConfigService.create(new McpServerRequest(name, "SSE", "http://localhost:9/sse", null, null, null, null, "ENABLED"));
        assertThatThrownBy(() -> mcpConfigService.create(
                        new McpServerRequest(name, "SSE", "http://localhost:9/sse", null, null, null, null, "ENABLED")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void 不存在的服务返回404业务错误() {
        assertThatThrownBy(() -> mcpConfigService.get(999_999_999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("MCP Server 不存在");
    }
}
