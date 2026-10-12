package com.myagent.agui;

import com.myagent.agentconfig.cache.AgentCacheInvalidator;
import com.myagent.agentconfig.entity.Agent;
import com.myagent.agentconfig.mapper.AgentMapper;
import com.myagent.agentconfig.mapper.AgentMcpServerMapper;
import com.myagent.agentconfig.mapper.AgentSkillMapper;
import com.myagent.mcpconfig.entity.McpServer;
import com.myagent.mcpconfig.mapper.McpServerMapper;
import com.myagent.common.BizException;
import com.myagent.modelconfig.entity.LlmModel;
import com.myagent.modelconfig.entity.LlmProvider;
import com.myagent.modelconfig.mapper.LlmModelMapper;
import com.myagent.modelconfig.mapper.LlmProviderMapper;
import com.myagent.common.CryptoService;
import com.myagent.skillconfig.mapper.SkillMapper;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 核心翻译器：把数据库里的 Agent 配置行翻译成 AgentScope 的 {@link HarnessAgent}。
 *
 * <p>职责（技术方案第 4 节）：
 * <ul>
 *   <li>系统提示词、模型、采样参数、最大迭代
 *   <li>权限模式 → AgentScope 权限上下文（HITL 基础）
 *   <li>Skill 与 MCP 工具装载（Phase 5 / Phase 6 接入，见 {@code TODO(P5)} / {@code TODO(P6)}）
 * </ul>
 *
 * <p>缓存：按 agent 主键缓存构建结果，配置变更时通过 {@link #invalidate(Long)} 失效。
 * 门禁 G2：AgentScope API 只出现在 {@code agui} 包。
 */
@Component
public class AgentFactory implements AgentCacheInvalidator, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final AgentMapper agentMapper;
    private final LlmModelMapper llmModelMapper;
    private final LlmProviderMapper llmProviderMapper;
    private final CryptoService cryptoService;
    private final ModelFactory modelFactory;
    private final AguiProperties aguiProperties;
    private final SkillRepositorySync skillRepositorySync;
    private final AgentSkillMapper agentSkillMapper;
    private final SkillMapper skillMapper;
    private final AgentMcpServerMapper agentMcpServerMapper;
    private final McpServerMapper mcpServerMapper;
    private final McpToolkitFactory mcpToolkitFactory;
    // Phase 11 / D23：会话状态与长期记忆入库（官方 SPI 的 PostgreSQL 实现）
    private final com.myagent.agui.memory.PgAgentStateStore pgAgentStateStore;
    private final io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem memoryRemoteFilesystem;

        /** 缓存键：{@code "12"} 或 {@code "12|3,5"}（含会话级技能时），见 build(Long, List)。 */
    private final Map<String, HarnessAgent> cache = new ConcurrentHashMap<>();

    public AgentFactory(
            AgentMapper agentMapper,
            LlmModelMapper llmModelMapper,
            LlmProviderMapper llmProviderMapper,
            CryptoService cryptoService,
            ModelFactory modelFactory,
            AguiProperties aguiProperties,
            SkillRepositorySync skillRepositorySync,
            AgentSkillMapper agentSkillMapper,
            SkillMapper skillMapper,
            AgentMcpServerMapper agentMcpServerMapper,
            McpServerMapper mcpServerMapper,
            McpToolkitFactory mcpToolkitFactory,
            com.myagent.agui.memory.PgAgentStateStore pgAgentStateStore,
            io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem memoryRemoteFilesystem) {
        this.agentMapper = agentMapper;
        this.llmModelMapper = llmModelMapper;
        this.llmProviderMapper = llmProviderMapper;
        this.cryptoService = cryptoService;
        this.modelFactory = modelFactory;
        this.aguiProperties = aguiProperties;
        this.skillRepositorySync = skillRepositorySync;
        this.agentSkillMapper = agentSkillMapper;
        this.skillMapper = skillMapper;
        this.agentMcpServerMapper = agentMcpServerMapper;
        this.mcpServerMapper = mcpServerMapper;
        this.mcpToolkitFactory = mcpToolkitFactory;
        this.pgAgentStateStore = pgAgentStateStore;
        this.memoryRemoteFilesystem = memoryRemoteFilesystem;
    }

    /** 按 Agent 主键构建（带缓存）。 */
    public HarnessAgent build(Long agentDbId) {
        return build(agentDbId, java.util.List.of());
    }

    /**
     * 按 Agent 主键 + 会话级额外技能构建（D21：`/` 选中的 Skill 并入该会话的技能集合）。
     *
     * <p>缓存键为 {@code agentDbId|排序后的技能id}，因此不同技能组合互不干扰；
     * {@link #invalidate(Long)} 会清掉该 agent 的所有组合。
     */
    public HarnessAgent build(Long agentDbId, java.util.List<Long> extraSkillIds) {
        java.util.List<Long> extras = extraSkillIds == null
                ? java.util.List.of()
                : extraSkillIds.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        String cacheKey = extras.isEmpty()
                ? String.valueOf(agentDbId)
                : agentDbId + "|" + extras.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));

        HarnessAgent cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Agent agent = agentMapper.selectById(agentDbId);
        if (agent == null) {
            throw BizException.notFound("Agent 不存在: " + agentDbId);
        }
        if (!"ENABLED".equals(agent.getStatus())) {
            throw BizException.badRequest("Agent 已禁用: " + agent.getAgentId());
        }
        HarnessAgent built = doBuild(agent, extras);
        HarnessAgent previous = cache.putIfAbsent(cacheKey, built);
        if (previous != null) {
            closeQuietly(built);
            return previous;
        }
        return built;
    }

    /** 按业务标识 agent_id 构建。 */
    public HarnessAgent buildByAgentId(String agentId) {
        Agent agent = agentMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Agent>lambdaQuery()
                        .eq(Agent::getAgentId, agentId));
        if (agent == null) {
            throw BizException.notFound("Agent 不存在: " + agentId);
        }
        return build(agent.getId());
    }

    /**
     * 把外部引用解析为 Agent 主键：纯数字视为主键，否则按业务标识 agent_id 查。
     *
     * @return 找不到时返回 null（由调用方决定报错方式）
     */
    public Long resolveAgentDbId(String reference) {
        if (!org.springframework.util.StringUtils.hasText(reference)) {
            return null;
        }
        String trimmed = reference.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            Long id = Long.valueOf(trimmed);
            return agentMapper.selectById(id) == null ? null : id;
        }
        Agent agent = agentMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Agent>lambdaQuery()
                        .eq(Agent::getAgentId, trimmed));
        return agent == null ? null : agent.getId();
    }

    private HarnessAgent doBuild(Agent agent, java.util.List<Long> extraSkillIds) {
        LlmModel model = agent.getModelId() == null ? null : llmModelMapper.selectById(agent.getModelId());
        if (model == null) {
            throw BizException.badRequest("Agent 未绑定有效模型: " + agent.getAgentId());
        }
        LlmProvider provider = llmProviderMapper.selectById(model.getProviderId());
        if (provider == null) {
            throw BizException.badRequest("模型对应的供应商不存在: " + model.getProviderId());
        }
        if (!"ENABLED".equals(provider.getStatus())) {
            throw BizException.badRequest("供应商已禁用: " + provider.getName());
        }

        String apiKey = cryptoService.decrypt(provider.getApiKeyCipher());
        // Phase 15：模型级显式列（温度/Top-P/输出长度）优先于历史 params_json，二者合并后交给模型工厂
        Map<String, Object> params = new java.util.HashMap<>(
                model.getParams() == null ? Map.of() : model.getParams());
        if (model.getTemperature() != null) {
            params.put("temperature", model.getTemperature());
        }
        if (model.getTopP() != null) {
            params.put("topP", model.getTopP());
        }
        if (model.getMaxTokens() != null) {
            params.put("maxTokens", model.getMaxTokens());
        }
        ChatModelBase chatModel =
                modelFactory.create(provider.getProviderType(), provider.getBaseUrl(), apiKey, model.getModelId(), params, true);

        // 参数优先级：Agent 级覆盖 > 模型级（模型级也必须生效，不能被空 options 覆盖掉）
        GenerateOptions.Builder options = GenerateOptions.builder();
        if (agent.getTemperature() != null) {
            options.temperature(agent.getTemperature().doubleValue());
        } else if (model.getTemperature() != null) {
            options.temperature(model.getTemperature().doubleValue());
        }
        if (agent.getMaxTokens() != null) {
            options.maxTokens(agent.getMaxTokens());
        } else if (model.getMaxTokens() != null) {
            options.maxTokens(model.getMaxTokens());
        }
        if (model.getTopP() != null) {
            options.topP(model.getTopP().doubleValue());
        }

        // Skill：官方仓库 + 按绑定过滤（只暴露该 Agent 绑定的启用 Skill）
        // 技能集合 = Agent 绑定的 + 会话级选中的（D21），去重后交给官方 SkillFilter
        java.util.LinkedHashSet<Long> allSkillIds = new java.util.LinkedHashSet<>(
                agentSkillMapper.findSkillIds(agent.getId()));
        if (extraSkillIds != null) {
            allSkillIds.addAll(extraSkillIds);
        }
        List<String> boundSkillNames = allSkillIds.isEmpty()
                ? List.of()
                : skillMapper.findNamesByIds(new java.util.ArrayList<>(allSkillIds));
        SkillFilter skillFilter = boundSkillNames.isEmpty()
                ? SkillFilter.none()
                : SkillFilter.only(boundSkillNames.toArray(String[]::new));

        // MCP：按绑定注册官方 MCP 工具（工具由框架按 Server 命名空间暴露）
        List<Long> boundMcpIds = agentMcpServerMapper.findMcpServerIds(agent.getId());
        List<McpServer> mcpServers = boundMcpIds.isEmpty()
                ? List.of()
                : mcpServerMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<McpServer>lambdaQuery()
                                .in(McpServer::getId, boundMcpIds)
                                .eq(McpServer::getStatus, "ENABLED"));
        Toolkit toolkit = mcpToolkitFactory.buildToolkit(mcpServers, isDemoAgent(agent.getAgentId()));

        HarnessAgent built = HarnessAgent.builder()
                .name(agent.getName())
                .agentId(agent.getAgentId())
                .sysPrompt(agent.getSysPrompt())
                .model(chatModel)
                .maxIters(agent.getMaxIterations() == null ? 20 : agent.getMaxIterations())
                .workspace(java.nio.file.Path.of(aguiProperties.workspaceRoot()))
                .generateOptions(options.build())
                .skillRepository(skillRepositorySync.newRepository())
                .skillFilter(skillFilter)
                .toolkit(toolkit)
                // Phase 11 / D23：框架会话状态落数据库（替换默认 JsonFileAgentStateStore）
                .stateStore(pgAgentStateStore)
                // 上下文治理（官方能力）：历史超阈值就压缩；超长工具结果（例如被读进来的大文件正文）
                // 外置到文件、只留预览。没有这两个配置时，上下文会无上限增长——实测出现过
                // 1.8MB 的会话状态，向模型请求 127 万 tokens 直接被厂商拒绝（context_length_exceeded）。
                .compaction(CompactionConfig.builder()
                        // Phase 15：上下文长度（模型窗口）也要生效——压缩阈值取「配置值」与
                        // 「窗口的 60%」中的较小者，避免窗口小的模型被压爆、窗口大的模型压得过勤。
                        .triggerTokens(compactionTriggerFor(model.getContextWindow()))
                        .keepTokens(aguiProperties.compactionKeepTokens())
                        .build())
                .toolResultEviction(ToolResultEvictionConfig.builder()
                        .maxResultChars(aguiProperties.toolResultMaxChars())
                        .previewChars(aguiProperties.toolResultPreviewChars())
                        .build())
                // Phase 11 / D23：工作区 memory/** 经官方 CompositeFilesystem 路由到数据库
                .filesystemRoute(MEMORY_ROUTE_PREFIX, memoryRemoteFilesystem)
                // 权限上下文必须在 toolkit 之后构建：ask 规则要覆盖 Agent 实际拥有的工具
                .permissionContext(buildPermissionContext(agent.getPermissionMode(), toolkit))
                .build();
        log.info(
                "构建 Agent 完成: {} (mode={}, skills={}, mcpServers={})",
                agent.getAgentId(),
                agent.getPermissionMode(),
                boundSkillNames,
                mcpServers.stream().map(McpServer::getName).toList());
        return built;
    }


    /** 压缩触发阈值：配置值与「模型窗口的 60%」取小（模型未配窗口时用配置值）。 */
    private int compactionTriggerFor(Integer contextWindow) {
        int configured = aguiProperties.compactionTriggerTokens();
        if (contextWindow == null || contextWindow <= 0) {
            return configured;
        }
        int fromWindow = (int) (contextWindow * 0.6);
        return Math.max(1000, Math.min(configured, fromWindow));
    }

    /** 权限模式 → AgentScope 权限模式。 */
    static PermissionMode toPermissionMode(String platformMode) {
        String mode = platformMode == null ? "" : platformMode.trim().toUpperCase();
        return switch (mode) {
            case "ALWAYS_ALLOW" -> PermissionMode.BYPASS;
            case "ALWAYS_DENY" -> PermissionMode.DONT_ASK;
            default -> PermissionMode.DEFAULT;
        };
    }

    /** demo-agent 的标识前缀；带该前缀的 Agent 额外获得 demo 卡片工具（Task 7.2）。 */
    private static final String DEMO_AGENT_PREFIX = "demo";

    /** 长期记忆在（运行时数据根下的）目录名；路由到数据库的前缀。 */
    static final String MEMORY_ROUTE_PREFIX = "memory";

    static boolean isDemoAgent(String agentId) {
        return agentId != null && agentId.trim().toLowerCase().startsWith(DEMO_AGENT_PREFIX);
    }

    /**
     * 构建权限上下文。
     *
     * <p>实测结论（Task 7.1）：仅把 mode 设为 DEFAULT **不会**触发审批——工具会直接执行。
     * 要真正拦下来，必须显式添加 {@link PermissionBehavior#ASK} 规则。
     *
     * <p>ask 规则直接取自 Agent 实际拥有的工具（{@link Toolkit#getToolNames()}），而不是仅靠
     * 同步过的工具缓存——否则同步未跑或失败时会漏拦。
     */
    static PermissionContextState buildPermissionContext(String platformMode, Toolkit toolkit) {
        PermissionContextState.Builder builder =
                PermissionContextState.builder().mode(toPermissionMode(platformMode));
        if (!"REQUIRE_APPROVAL".equalsIgnoreCase(platformMode == null ? "" : platformMode.trim())) {
            return builder.build();
        }
        java.util.Set<String> toolNames = new java.util.LinkedHashSet<>();
        toolNames.add("*");
        if (toolkit != null) {
            toolNames.addAll(toolkit.getToolNames());
        }
        for (String toolName : toolNames) {
            builder.addAskRule(toolName, new PermissionRule(toolName, null, PermissionBehavior.ASK, "myagent"));
        }
        return builder.build();
    }

    @Override
    public void invalidate(Long agentDbId) {
        // 组合缓存键形如 "12" 或 "12|3,5"：两类都要清
        String exact = String.valueOf(agentDbId);
        String prefix = agentDbId + "|";
        cache.keySet().stream()
                .filter(k -> k.equals(exact) || k.startsWith(prefix))
                .toList()
                .forEach(k -> closeQuietly(cache.remove(k)));
    }

    @Override
    public void invalidateAll() {
        // 缓存键现在是组合键（agent id 或 agent id + 技能组合），直接整体清空
        cache.keySet().forEach(k -> closeQuietly(cache.remove(k)));
    }

    private static void closeQuietly(HarnessAgent agent) {
        if (agent == null) {
            return;
        }
        try {
            agent.close();
        } catch (Exception ex) {
            log.warn("关闭 HarnessAgent 失败: {}", ex.getMessage());
        }
    }

    @Override
    public void close() {
        invalidateAll();
    }
}
