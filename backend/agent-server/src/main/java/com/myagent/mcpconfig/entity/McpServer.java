package com.myagent.mcpconfig.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** mcp_server 表实体；args/env/headers/tool_cache 以 JSONB 存储。 */
@TableName(value = "mcp_server", autoResultMap = true)
public class McpServer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** STDIO / SSE / STREAMABLE_HTTP */
    private String transport;

    private String endpointUrl;

    private String command;

    @TableField(value = "args_json", typeHandler = JacksonTypeHandler.class)
    private List<String> args;

    @TableField(value = "env_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, String> env;

    @TableField(value = "headers_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, String> headers;

    @TableField(value = "tool_cache", typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> toolCache;

    private OffsetDateTime lastSyncedAt;

    private String status;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public List<Map<String, Object>> getToolCache() {
        return toolCache;
    }

    public void setToolCache(List<Map<String, Object>> toolCache) {
        this.toolCache = toolCache;
    }

    public OffsetDateTime getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(OffsetDateTime lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
