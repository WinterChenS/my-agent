package com.myagent.mcpconfig.probe;

import java.util.List;
import java.util.Map;

/**
 * 一个 MCP Server 的连接描述（与数据库实体解耦，便于实现侧只依赖必要字段）。
 *
 * @param name 服务名（作为工具命名空间）
 * @param transport STDIO / SSE / STREAMABLE_HTTP
 * @param endpointUrl SSE / STREAMABLE_HTTP 的地址
 * @param command STDIO 的启动命令
 * @param args STDIO 的参数
 * @param env STDIO 的环境变量
 * @param headers HTTP 传输的请求头
 */
public record McpServerSpec(
        String name,
        String transport,
        String endpointUrl,
        String command,
        List<String> args,
        Map<String, String> env,
        Map<String, String> headers) {}
