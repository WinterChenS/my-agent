package com.myagent.agui;

import com.myagent.mcpconfig.entity.McpServer;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按 Agent 绑定的 MCP Server 构建 {@link Toolkit}。
 *
 * <p>门禁 G8：MCP 的协议实现全部用官方件——客户端用官方 {@link McpClientBuilder} 构建，
 * 注册走官方 {@code Toolkit.registerMcpClient}，不自己实现 MCP 协议。
 *
 * <p>与官方默认行为的唯一差别：官方注册的是 Server 提供的**裸工具名**（实测 {@code echo}），
 * 只把工具放进以 Server 命名的工具组。本平台冻结契约要求工具名带 Server 命名空间
 * （{@code server__tool}，见 mcp-integration spec），因此在官方客户端外面套一层
 * {@link NamespacedMcpClientWrapper} 负责加/去前缀。
 */
@Component
public class McpToolkitFactory {

    private static final Logger log = LoggerFactory.getLogger(McpToolkitFactory.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** 注册（含 STDIO 冷启动 npx 拉包）可能较慢，单独给更宽松的等待。 */
    private static final Duration REGISTER_TIMEOUT = Duration.ofSeconds(90);

    /** 平台 transport → 官方 McpServerConfig.transport 字面量。 */
    static String toOfficialTransport(String platformTransport) {
        String transport = platformTransport == null ? "" : platformTransport.trim().toUpperCase();
        return switch (transport) {
            case "STDIO" -> "stdio";
            case "SSE" -> "sse";
            case "STREAMABLE_HTTP" -> "streamable-http";
            default -> throw com.myagent.common.BizException.badRequest(
                    "不支持的 transport: " + platformTransport);
        };
    }

    /**
     * 构建 Toolkit 并注册给定的 MCP Server（工具名带 {@code server__} 命名空间）。
     *
     * <p>单个 Server 注册失败不影响其它 Server（逐个 try/catch 并告警）。
     *
     * @param includeDemoTools 是否额外注册 demo 卡片工具（仅 demo-agent）
     */
    public Toolkit buildToolkit(List<McpServer> servers, boolean includeDemoTools) {
        Toolkit toolkit = new Toolkit();
        if (includeDemoTools) {
            DemoCardTools.tools().forEach(toolkit::registerAgentTool);
            log.info("已注册 {} 个 demo 卡片工具", DemoCardTools.tools().size());
        }
        if (servers == null || servers.isEmpty()) {
            return toolkit;
        }
        for (McpServer server : servers) {
            try {
                McpClientWrapper official = buildClient(server);
                McpClientWrapper namespaced = new NamespacedMcpClientWrapper(server.getName(), official);
                // 官方注册入口：内部会 initialize + listTools，并按我们包装后的名字注册工具
                toolkit.registerMcpClient(namespaced).block(REGISTER_TIMEOUT);
                log.info("已注册 MCP Server {}（工具名带 {}__ 前缀）", server.getName(), server.getName());
            } catch (Exception ex) {
                log.warn("注册 MCP Server 失败 server={}: {}", server.getName(), ex.toString());
            }
        }
        return toolkit;
    }

    private McpClientWrapper buildClient(McpServer server) {
        McpClientBuilder builder = McpClientBuilder.create(server.getName())
                .timeout(TIMEOUT)
                .initializationTimeout(TIMEOUT);
        String transport = server.getTransport() == null ? "" : server.getTransport().trim().toUpperCase();
        switch (transport) {
            case "STDIO" -> builder.stdioTransport(
                    server.getCommand(),
                    server.getArgs() == null ? List.of() : server.getArgs(),
                    server.getEnv() == null ? java.util.Map.of() : server.getEnv());
            case "SSE" -> builder.sseTransport(server.getEndpointUrl());
            case "STREAMABLE_HTTP" -> builder.streamableHttpTransport(server.getEndpointUrl());
            default -> throw com.myagent.common.BizException.badRequest(
                    "不支持的 transport: " + server.getTransport());
        }
        if (server.getHeaders() != null && !server.getHeaders().isEmpty()) {
            builder.headers(server.getHeaders());
        }
        return builder.buildSync();
    }
}
