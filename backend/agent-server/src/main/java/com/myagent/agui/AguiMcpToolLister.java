package com.myagent.agui;

import com.myagent.common.BizException;
import com.myagent.mcpconfig.probe.McpServerSpec;
import com.myagent.mcpconfig.probe.McpToolInfo;
import com.myagent.mcpconfig.probe.McpToolLister;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 用官方 {@code McpClientBuilder} 连接 MCP Server 并列出工具。
 *
 * <p>门禁 G8：连接、握手、工具发现全部走官方 SDK；本类不解析任何 JSON-RPC。
 * 门禁 G2：位于 {@code agui} 包，配置类包只依赖 {@link McpToolLister} 接口。
 */
@Component
public class AguiMcpToolLister implements McpToolLister {

    private static final Logger log = LoggerFactory.getLogger(AguiMcpToolLister.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    @Override
    public List<McpToolInfo> listTools(McpServerSpec spec) {
        McpClientWrapper client = null;
        try {
            client = buildClient(spec);
            client.initialize().block(DEFAULT_TIMEOUT);
            List<McpSchema.Tool> tools = client.listTools().block(DEFAULT_TIMEOUT);
            if (tools == null) {
                return List.of();
            }
            return tools.stream().map(AguiMcpToolLister::toToolInfo).toList();
        } catch (Exception ex) {
            log.warn("MCP 工具同步失败 server={} transport={}: {}", spec.name(), spec.transport(), ex.toString());
            throw BizException.badRequest(
                    "MCP 连接失败（" + spec.name() + "）: " + rootMessage(ex));
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ex) {
                    log.debug("关闭 MCP 客户端失败: {}", ex.getMessage());
                }
            }
        }
    }

    private McpClientWrapper buildClient(McpServerSpec spec) {
        McpClientBuilder builder = McpClientBuilder.create(spec.name()).timeout(DEFAULT_TIMEOUT);
        String transport = spec.transport() == null ? "" : spec.transport().trim().toUpperCase();
        switch (transport) {
            case "STDIO" -> builder.stdioTransport(spec.command(), spec.args(), spec.env());
            case "SSE" -> builder.sseTransport(spec.endpointUrl());
            case "STREAMABLE_HTTP" -> builder.streamableHttpTransport(spec.endpointUrl());
            default -> throw BizException.badRequest("不支持的 transport: " + spec.transport());
        }
        if (spec.headers() != null && !spec.headers().isEmpty()) {
            builder.headers(spec.headers());
        }
        return builder.buildSync();
    }

    private static McpToolInfo toToolInfo(McpSchema.Tool tool) {
        Map<String, Object> schema = new LinkedHashMap<>();
        McpSchema.JsonSchema input = tool.inputSchema();
        if (input != null) {
            schema.put("type", input.type());
            schema.put("properties", input.properties());
            schema.put("required", input.required());
        }
        return new McpToolInfo(
                tool.name(),
                StringUtils.hasText(tool.description()) ? tool.description() : tool.title(),
                schema);
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
