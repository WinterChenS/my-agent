package com.myagent.agui;

import com.myagent.agentconfig.cache.AgentCacheInvalidator;
import com.myagent.agentconfig.entity.Agent;
import com.myagent.agentconfig.mapper.AgentMapper;
import com.myagent.common.BizException;
import com.myagent.modelconfig.entity.LlmModel;
import com.myagent.modelconfig.entity.LlmProvider;
import com.myagent.modelconfig.mapper.LlmModelMapper;
import com.myagent.modelconfig.mapper.LlmProviderMapper;
import com.myagent.common.CryptoService;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.harness.agent.HarnessAgent;
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

    private final Map<Long, HarnessAgent> cache = new ConcurrentHashMap<>();

    public AgentFactory(
            AgentMapper agentMapper,
            LlmModelMapper llmModelMapper,
            LlmProviderMapper llmProviderMapper,
            CryptoService cryptoService,
            ModelFactory modelFactory,
            AguiProperties aguiProperties) {
        this.agentMapper = agentMapper;
        this.llmModelMapper = llmModelMapper;
        this.llmProviderMapper = llmProviderMapper;
        this.cryptoService = cryptoService;
        this.modelFactory = modelFactory;
        this.aguiProperties = aguiProperties;
    }

    /** 按 Agent 主键构建（带缓存）。 */
    public HarnessAgent build(Long agentDbId) {
        HarnessAgent cached = cache.get(agentDbId);
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
        HarnessAgent built = doBuild(agent);
        HarnessAgent previous = cache.putIfAbsent(agentDbId, built);
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

    private HarnessAgent doBuild(Agent agent) {
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
        Map<String, Object> params = model.getParams() == null ? Map.of() : model.getParams();
        ChatModelBase chatModel =
                modelFactory.create(provider.getProviderType(), provider.getBaseUrl(), apiKey, model.getModelId(), params, true);

        GenerateOptions.Builder options = GenerateOptions.builder();
        if (agent.getTemperature() != null) {
            options.temperature(agent.getTemperature().doubleValue());
        }
        if (agent.getMaxTokens() != null) {
            options.maxTokens(agent.getMaxTokens());
        }

        // TODO(P5): 装载绑定的 Skill（官方 skill-postgresql-repository）
        // TODO(P6): 装载绑定的 MCP Server 工具（官方 McpClientManager）

        HarnessAgent built = HarnessAgent.builder()
                .name(agent.getName())
                .agentId(agent.getAgentId())
                .sysPrompt(agent.getSysPrompt())
                .model(chatModel)
                .maxIters(agent.getMaxIterations() == null ? 20 : agent.getMaxIterations())
                .workspace(java.nio.file.Path.of(aguiProperties.workspaceRoot()))
                .generateOptions(options.build())
                .permissionContext(PermissionContextState.builder()
                        .mode(toPermissionMode(agent.getPermissionMode()))
                        .build())
                .build();
        log.info("构建 Agent 完成: {} (mode={})", agent.getAgentId(), agent.getPermissionMode());
        return built;
    }

    /**
     * 平台权限模式 → AgentScope 权限模式。
     *
     * <p>AgentScope 的枚举语义与平台配置并非一一对应，这里显式映射并记录理由：
     * <ul>
     *   <li>{@code ALWAYS_ALLOW} → {@code BYPASS}：跳过权限检查，直接执行
     *   <li>{@code ALWAYS_DENY} → {@code DONT_ASK}：不询问，直接拒绝
     *   <li>{@code REQUIRE_APPROVAL} → {@code DEFAULT}：需要时发起审批（HITL 主链路）
     * </ul>
     */
    static PermissionMode toPermissionMode(String platformMode) {
        String mode = platformMode == null ? "" : platformMode.trim().toUpperCase();
        return switch (mode) {
            case "ALWAYS_ALLOW" -> PermissionMode.BYPASS;
            case "ALWAYS_DENY" -> PermissionMode.DONT_ASK;
            default -> PermissionMode.DEFAULT;
        };
    }

    @Override
    public void invalidate(Long agentDbId) {
        HarnessAgent removed = cache.remove(agentDbId);
        closeQuietly(removed);
    }

    @Override
    public void invalidateAll() {
        cache.keySet().forEach(this::invalidate);
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
