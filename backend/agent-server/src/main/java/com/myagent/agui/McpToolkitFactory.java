package com.myagent.agui;

import com.myagent.mcpconfig.entity.McpServer;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按 Agent 绑定的 MCP Server 构建 {@link Toolkit}。
 *
 * <p>门禁 G8：注册与工具暴露走官方 {@link McpServerRegistrar}，工具由框架按 Server 命名空间暴露。
 */
@Component
public class McpToolkitFactory {

    private static final Logger log = LoggerFactory.getLogger(McpToolkitFactory.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

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
     * 构建 Toolkit 并注册给定的 MCP Server。
     *
     * <p>单个 Server 注册失败不影响其它 Server（由官方 registrar 逐个处理并记录）。
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
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();
        for (McpServer server : servers) {
            configs.put(server.getName(), toConfig(server));
        }
        McpServerRegistrar.register(toolkit, configs);
        log.info("已注册 {} 个 MCP Server 到 Toolkit: {}", configs.size(), configs.keySet());
        return toolkit;
    }

    private McpServerConfig toConfig(McpServer server) {
        McpServerConfig config = new McpServerConfig();
        config.setTransport(toOfficialTransport(server.getTransport()));
        config.setCommand(server.getCommand());
        config.setArgs(server.getArgs() == null ? List.of() : server.getArgs());
        config.setEnv(server.getEnv() == null ? Map.of() : server.getEnv());
        config.setUrl(server.getEndpointUrl());
        config.setHeaders(server.getHeaders() == null ? Map.of() : server.getHeaders());
        config.setTimeout(TIMEOUT);
        return config;
    }
}
