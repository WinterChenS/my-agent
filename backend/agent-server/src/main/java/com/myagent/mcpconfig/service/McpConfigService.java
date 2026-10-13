package com.myagent.mcpconfig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.myagent.agentconfig.cache.AgentCacheInvalidator;
import com.myagent.common.BizException;
import com.myagent.common.PageResult;
import com.myagent.mcpconfig.dto.McpServerRequest;
import com.myagent.mcpconfig.dto.McpServerResponse;
import com.myagent.mcpconfig.entity.McpServer;
import com.myagent.mcpconfig.mapper.McpServerMapper;
import com.myagent.mcpconfig.probe.McpServerSpec;
import com.myagent.mcpconfig.probe.McpToolInfo;
import com.myagent.mcpconfig.probe.McpToolLister;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** MCP Server 配置与工具同步。 */
@Service
public class McpConfigService {

    private static final Set<String> TRANSPORTS = Set.of("STDIO", "SSE", "STREAMABLE_HTTP");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");

    private final McpServerMapper mcpServerMapper;
    private final McpToolLister mcpToolLister;
    private final AgentCacheInvalidator agentCacheInvalidator;

    public McpConfigService(
            McpServerMapper mcpServerMapper,
            McpToolLister mcpToolLister,
            AgentCacheInvalidator agentCacheInvalidator) {
        this.mcpServerMapper = mcpServerMapper;
        this.mcpToolLister = mcpToolLister;
        this.agentCacheInvalidator = agentCacheInvalidator;
    }

    /**
     * 列表（分页 + 筛选）。
     *
     * <p>筛选项都是可选的：{@code keyword} 按名称模糊匹配，{@code status}/{@code transport} 精确匹配。
     * 之前只能一页刷到底，MCP 服务器一多就难找（用户反馈）。
     */
    public PageResult<McpServerResponse> list(
            long current, long size, String keyword, String status, String transport) {
        var query = Wrappers.<McpServer>lambdaQuery().orderByAsc(McpServer::getId);
        if (StringUtils.hasText(keyword)) {
            query.like(McpServer::getName, keyword.trim());
        }
        if (StringUtils.hasText(status)) {
            query.eq(McpServer::getStatus, status.trim().toUpperCase());
        }
        if (StringUtils.hasText(transport)) {
            query.eq(McpServer::getTransport, transport.trim().toUpperCase());
        }
        Page<McpServer> page = mcpServerMapper.selectPage(new Page<>(current, size), query);
        return PageResult.of(
                page.getRecords().stream().map(McpConfigService::toResponse).toList(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize());
    }

    public McpServerResponse get(Long id) {
        return toResponse(requireServer(id));
    }

    @Transactional
    public McpServerResponse create(McpServerRequest request) {
        validate(request);
        ensureNameUnique(request.name(), null);
        McpServer server = new McpServer();
        apply(server, request);
        mcpServerMapper.insert(server);
        agentCacheInvalidator.invalidateAll();
        return toResponse(server);
    }

    @Transactional
    public McpServerResponse update(Long id, McpServerRequest request) {
        McpServer server = requireServer(id);
        validate(request);
        ensureNameUnique(request.name(), id);
        apply(server, request);
        mcpServerMapper.updateById(server);
        agentCacheInvalidator.invalidateAll();
        return toResponse(server);
    }

    @Transactional
    public void delete(Long id) {
        requireServer(id);
        long bindings = mcpServerMapper.countBindings(id);
        if (bindings > 0) {
            throw BizException.conflict("该 MCP Server 被 " + bindings + " 个 Agent 绑定，无法删除");
        }
        mcpServerMapper.deleteById(id);
        agentCacheInvalidator.invalidateAll();
    }

    /**
     * 连接并拉取工具清单，写入 {@code tool_cache} 与 {@code last_synced_at}。
     *
     * <p>连接失败返回业务错误（不是 500），且不影响其它功能。
     */
    @Transactional
    public List<McpToolInfo> syncTools(Long id) {
        McpServer server = requireServer(id);
        List<McpToolInfo> tools = mcpToolLister.listTools(toSpec(server));

        server.setToolCache(tools.stream().map(McpConfigService::toCacheEntry).toList());
        server.setLastSyncedAt(OffsetDateTime.now());
        mcpServerMapper.updateById(server);
        return tools;
    }

    public static McpServerSpec toSpec(McpServer server) {
        return new McpServerSpec(
                server.getName(),
                server.getTransport(),
                server.getEndpointUrl(),
                server.getCommand(),
                server.getArgs() == null ? List.of() : server.getArgs(),
                server.getEnv() == null ? Map.of() : server.getEnv(),
                server.getHeaders() == null ? Map.of() : server.getHeaders());
    }

    private static Map<String, Object> toCacheEntry(McpToolInfo tool) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", tool.name());
        entry.put("description", tool.description());
        entry.put("inputSchema", tool.inputSchema());
        return entry;
    }

    private void apply(McpServer server, McpServerRequest request) {
        server.setName(request.name());
        server.setTransport(request.transport().trim().toUpperCase());
        server.setEndpointUrl(request.endpointUrl());
        server.setCommand(request.command());
        server.setArgs(request.args() == null ? List.of() : request.args());
        server.setEnv(request.env() == null ? Map.of() : request.env());
        server.setHeaders(request.headers() == null ? Map.of() : request.headers());
        server.setStatus(StringUtils.hasText(request.status())
                ? request.status().trim().toUpperCase()
                : "ENABLED");
    }

    private void validate(McpServerRequest request) {
        String transport = request.transport() == null ? "" : request.transport().trim().toUpperCase();
        if (!TRANSPORTS.contains(transport)) {
            throw BizException.badRequest("transport 非法: " + request.transport());
        }
        if ("STDIO".equals(transport) && !StringUtils.hasText(request.command())) {
            throw BizException.badRequest("STDIO 传输必须提供 command");
        }
        if (!"STDIO".equals(transport) && !StringUtils.hasText(request.endpointUrl())) {
            throw BizException.badRequest(transport + " 传输必须提供 endpointUrl");
        }
        if (request.status() != null && !STATUSES.contains(request.status().trim().toUpperCase())) {
            throw BizException.badRequest("status 非法: " + request.status());
        }
    }

    private McpServer requireServer(Long id) {
        McpServer server = id == null ? null : mcpServerMapper.selectById(id);
        if (server == null) {
            throw BizException.notFound("MCP Server 不存在: " + id);
        }
        return server;
    }

    private void ensureNameUnique(String name, Long excludeId) {
        Long existing = mcpServerMapper.selectCount(Wrappers.<McpServer>lambdaQuery()
                .eq(McpServer::getName, name)
                .ne(excludeId != null, McpServer::getId, excludeId));
        if (existing > 0) {
            throw BizException.conflict("MCP Server 名称已存在: " + name);
        }
    }

    private static McpServerResponse toResponse(McpServer server) {
        return new McpServerResponse(
                server.getId(),
                server.getName(),
                server.getTransport(),
                server.getEndpointUrl(),
                server.getCommand(),
                server.getArgs(),
                server.getEnv(),
                server.getHeaders(),
                server.getToolCache(),
                server.getLastSyncedAt(),
                server.getStatus(),
                server.getCreatedAt(),
                server.getUpdatedAt());
    }
}
