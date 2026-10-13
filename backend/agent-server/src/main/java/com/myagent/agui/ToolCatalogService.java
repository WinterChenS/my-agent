package com.myagent.agui;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.agentconfig.entity.Agent;
import com.myagent.agentconfig.mapper.AgentMapper;
import com.myagent.mcpconfig.entity.McpServer;
import com.myagent.mcpconfig.mapper.McpServerMapper;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 「当前有哪些工具可以被模型调用」——供 Skill 编辑器里输入 {@code /} 时插入工具名。
 *
 * <p>两类来源，都**不另造轮子**：
 *
 * <ul>
 *   <li><b>MCP 工具</b>：取已同步的 {@code mcp_server.tool_cache}，并且用**模型实际看到的命名**
 *       {@code <serverName>__<toolName>}（平台给 MCP 工具加了命名空间，写错名字技能就调不到）。
 *   <li><b>本地工具</b>：直接问框架——建一次 Agent 后读 {@code HarnessAgent.getToolkit()}
 *       的 schema 列表（read_file / memory_search / agent_spawn 这些都由 AgentScope 官方
 *       harness 注册，平台**不做一套自己的本地工具管理**）。结果缓存，不重复构建。
 * </ul>
 */
@Service
public class ToolCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ToolCatalogService.class);

    /** 一条可插入的工具。 */
    public record ToolEntry(String name, String group, String hint) {}

    private final McpServerMapper mcpServerMapper;
    private final AgentMapper agentMapper;
    private final AgentFactory agentFactory;

    /** 本地工具清单缓存（框架内置工具在一次进程内不会变）。 */
    private volatile List<ToolEntry> localTools;

    public ToolCatalogService(
            McpServerMapper mcpServerMapper, AgentMapper agentMapper, AgentFactory agentFactory) {
        this.mcpServerMapper = mcpServerMapper;
        this.agentMapper = agentMapper;
        this.agentFactory = agentFactory;
    }

    /** MCP 工具 + 本地工具。 */
    public List<ToolEntry> list() {
        List<ToolEntry> all = new ArrayList<>(mcpTools());
        all.addAll(localTools());
        return all;
    }

    /** 已同步的 MCP 工具（带 {@code server__tool} 命名空间，与模型看到的一致）。 */
    public List<ToolEntry> mcpTools() {
        List<ToolEntry> entries = new ArrayList<>();
        try {
            List<McpServer> servers = mcpServerMapper.selectList(
                    Wrappers.<McpServer>lambdaQuery().eq(McpServer::getStatus, "ENABLED"));
            for (McpServer server : servers) {
                if (server.getToolCache() == null) {
                    continue;
                }
                for (Map<String, Object> tool : server.getToolCache()) {
                    Object rawName = tool.get("name");
                    if (rawName == null) {
                        continue;
                    }
                    String hint = tool.get("description") == null
                            ? null
                            : abbreviate(String.valueOf(tool.get("description")));
                    entries.add(new ToolEntry(
                            server.getName() + "__" + rawName, "MCP · " + server.getName(), hint));
                }
            }
        } catch (Exception ex) {
            log.warn("列出 MCP 工具失败: {}", ex.toString());
        }
        return entries;
    }

    /**
     * 框架注册的本地工具（只读，缓存）。
     *
     * <p>做法：挑一个启用中的 Agent 构建一次，读它的 Toolkit schema。
     * 这样工具名与说明**永远来自框架本身**，平台不维护第二份清单。
     */
    public List<ToolEntry> localTools() {
        List<ToolEntry> cached = localTools;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (localTools != null) {
                return localTools;
            }
            List<ToolEntry> entries = new ArrayList<>();
            try {
                Agent agent = agentMapper.selectOne(Wrappers.<Agent>lambdaQuery()
                        .eq(Agent::getStatus, "ENABLED")
                        .orderByAsc(Agent::getId)
                        .last("limit 1"));
                if (agent != null) {
                    Map<String, ToolEntry> dedup = new LinkedHashMap<>();
                    for (ToolSchema schema : agentFactory.build(agent.getId()).getToolkit().getToolSchemas()) {
                        dedup.put(
                                schema.getName(),
                                new ToolEntry(
                                        schema.getName(),
                                        "本地工具",
                                        schema.getDescription() == null
                                                ? null
                                                : abbreviate(schema.getDescription())));
                    }
                    entries.addAll(dedup.values());
                }
            } catch (Exception ex) {
                log.warn("列出本地工具失败（框架内置工具由 harness 注册）: {}", ex.toString());
            }
            localTools = List.copyOf(entries);
            return localTools;
        }
    }

    private static String abbreviate(String text) {
        String flat = text.replace('\n', ' ').strip();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }
}
