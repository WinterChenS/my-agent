package com.myagent.agui;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * 给 MCP 工具名加上 Server 命名空间前缀（{@code <serverName>__<toolName>}）。
 *
 * <p>背景：官方 {@code McpServerRegistrar} / {@code McpClientManager} 注册 MCP 工具时用的是
 * Server 提供的**裸工具名**（实测 {@code echo}），只把工具放进以 Server 命名的工具组。
 * 但本平台冻结的 mcp-integration 契约要求工具名带 Server 命名空间——否则多个 Server 出现同名工具时
 * Agent 会调错，且 HITL 的 ask 规则、审计里的工具名都无法区分来源。
 *
 * <p>做法：仍然走官方链路（{@link io.agentscope.core.tool.mcp.McpClientBuilder} 建官方客户端、
 * {@code Toolkit.registerMcpClient} 注册），只在这一层把 {@code listTools()} 的名字加上前缀，
 * 并在真正调用时把前缀去掉再交给官方客户端——不自己实现 MCP 协议（门禁 G8）。
 *
 * <p>注意：{@code McpTool.callAsync} 传给 {@code callTool} 的是**注册名**（带前缀），
 * 所以这里必须剥掉前缀，否则服务端收到 {@code server__echo} 会报未知工具。
 */
public class NamespacedMcpClientWrapper extends McpClientWrapper {

    /** 命名空间分隔符（契约：{@code server__tool}）。 */
    static final String SEPARATOR = "__";

    private final McpClientWrapper delegate;
    private final String prefix;

    public NamespacedMcpClientWrapper(String serverName, McpClientWrapper delegate) {
        super(serverName);
        this.delegate = delegate;
        this.prefix = serverName + SEPARATOR;
    }

    @Override
    public Mono<Void> initialize() {
        return delegate.initialize();
    }

    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        return delegate.listTools().map(tools -> tools.stream().map(this::rename).toList());
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments) {
        return delegate.callTool(strip(name), arguments);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(
            String name, Map<String, Object> arguments, Map<String, Object> meta) {
        return delegate.callTool(strip(name), arguments, meta);
    }

    @Override
    public McpSchema.Tool getCachedTool(String name) {
        return delegate.getCachedTool(strip(name));
    }

    @Override
    public void close() {
        delegate.close();
    }

    /** 暴露给测试与日志：某个工具在本平台注册后使用的名字。 */
    public String namespacedName(String toolName) {
        return prefix + toolName;
    }

    private String strip(String name) {
        return name != null && name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }

    private McpSchema.Tool rename(McpSchema.Tool tool) {
        return McpSchema.Tool.builder()
                .name(prefix + tool.name())
                .title(tool.title())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .outputSchema(tool.outputSchema())
                .annotations(tool.annotations())
                .meta(tool.meta())
                .build();
    }
}
