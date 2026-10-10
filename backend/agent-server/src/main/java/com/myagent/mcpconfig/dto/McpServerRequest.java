package com.myagent.mcpconfig.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/** MCP Server 新建/更新请求。 */
public record McpServerRequest(
        @NotBlank(message = "name 不能为空") String name,
        @NotBlank(message = "transport 不能为空") String transport,
        String endpointUrl,
        String command,
        List<String> args,
        Map<String, String> env,
        Map<String, String> headers,
        String status) {}
