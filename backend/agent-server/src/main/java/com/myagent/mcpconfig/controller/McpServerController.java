package com.myagent.mcpconfig.controller;

import com.myagent.common.PageResult;
import com.myagent.common.Result;
import com.myagent.mcpconfig.dto.McpServerRequest;
import com.myagent.mcpconfig.dto.McpServerResponse;
import com.myagent.mcpconfig.probe.McpToolInfo;
import com.myagent.mcpconfig.service.McpConfigService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** MCP Server 配置与工具同步。 */
@RestController
@RequestMapping("/api/mcp-servers")
public class McpServerController {

    private final McpConfigService service;

    public McpServerController(McpConfigService service) {
        this.service = service;
    }

    @GetMapping
    public Result<PageResult<McpServerResponse>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String transport) {
        return Result.ok(service.list(current, size, keyword, status, transport));
    }

    @GetMapping("/{id}")
    public Result<McpServerResponse> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    @PostMapping
    public Result<McpServerResponse> create(@Valid @RequestBody McpServerRequest request) {
        return Result.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public Result<McpServerResponse> update(
            @PathVariable Long id, @Valid @RequestBody McpServerRequest request) {
        return Result.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    /** 拉取工具清单，写入 tool_cache 并返回。 */
    @PostMapping("/{id}/sync-tools")
    public Result<List<McpToolInfo>> syncTools(@PathVariable Long id) {
        return Result.ok(service.syncTools(id));
    }
}
