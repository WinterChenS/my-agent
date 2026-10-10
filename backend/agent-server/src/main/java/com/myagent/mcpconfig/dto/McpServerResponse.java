package com.myagent.mcpconfig.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** MCP Server 响应（含工具缓存摘要与最近同步时间）。 */
public record McpServerResponse(
        Long id,
        String name,
        String transport,
        String endpointUrl,
        String command,
        List<String> args,
        Map<String, String> env,
        Map<String, String> headers,
        List<Map<String, Object>> toolCache,
        OffsetDateTime lastSyncedAt,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
