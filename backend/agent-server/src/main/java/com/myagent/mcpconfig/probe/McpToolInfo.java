package com.myagent.mcpconfig.probe;

import java.util.Map;

/**
 * 一个 MCP 工具的描述。
 *
 * @param name 工具名
 * @param description 工具说明
 * @param inputSchema 入参 JSON Schema
 */
public record McpToolInfo(String name, String description, Map<String, Object> inputSchema) {}
