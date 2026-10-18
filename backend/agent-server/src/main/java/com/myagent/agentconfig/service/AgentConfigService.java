package com.myagent.agentconfig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.myagent.agentconfig.cache.AgentCacheInvalidator;
import com.myagent.agentconfig.dto.AgentRequest;
import com.myagent.agentconfig.dto.AgentResponse;
import com.myagent.agentconfig.entity.Agent;
import com.myagent.agentconfig.mapper.AgentMapper;
import com.myagent.agentconfig.mapper.AgentMcpServerMapper;
import com.myagent.agentconfig.mapper.AgentSkillMapper;
import com.myagent.common.BizException;
import com.myagent.common.PageResult;
import com.myagent.modelconfig.entity.LlmModel;
import com.myagent.modelconfig.mapper.LlmModelMapper;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Agent 配置：CRUD 与 Skill / MCP 绑定。 */
@Service
public class AgentConfigService {

    private static final Set<String> PERMISSION_MODES =
            Set.of("ALWAYS_ALLOW", "ALWAYS_DENY", "REQUIRE_APPROVAL");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final int DEFAULT_MAX_ITERATIONS = 20;

    private final AgentMapper agentMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final AgentMcpServerMapper agentMcpServerMapper;
    private final LlmModelMapper llmModelMapper;
    private final AgentCacheInvalidator cacheInvalidator;
    /** 按资源授权：Agent 的可用用户白名单（空 = 对所有人开放）。 */
    private final com.myagent.agentconfig.mapper.AgentUserMapper agentUserMapper;

    public AgentConfigService(
            AgentMapper agentMapper,
            AgentSkillMapper agentSkillMapper,
            AgentMcpServerMapper agentMcpServerMapper,
            LlmModelMapper llmModelMapper,
            AgentCacheInvalidator cacheInvalidator,
            com.myagent.agentconfig.mapper.AgentUserMapper agentUserMapper) {
        this.agentMapper = agentMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.agentMcpServerMapper = agentMcpServerMapper;
        this.llmModelMapper = llmModelMapper;
        this.cacheInvalidator = cacheInvalidator;
        this.agentUserMapper = agentUserMapper;
    }

    /**
     * Agent 列表。
     *
     * <p>按资源授权：普通用户**只看到**"对所有人开放"或"显式授权给他"的 Agent；
     * 条件推给数据库（不是取全量再过滤），否则分页会错。
     */
    public PageResult<AgentResponse> list(long current, long size, Long userId, boolean isAdmin) {
        Page<Agent> page = agentMapper.selectPage(
                new Page<>(current, size),
                Wrappers.<Agent>lambdaQuery()
                        .apply(
                                !isAdmin,
                                """
                                (NOT EXISTS (SELECT 1 FROM agent_user au WHERE au.agent_id = agent.id)
                                 OR EXISTS (SELECT 1 FROM agent_user au2
                                            WHERE au2.agent_id = agent.id AND au2.user_id = {0}))
                                """,
                                userId)
                        .orderByAsc(Agent::getId));
        return PageResult.of(
                page.getRecords().stream().map(this::toResponse).toList(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize());
    }

    public AgentResponse get(Long id) {
        return toResponse(requireAgent(id));
    }

    /**
     * 该用户能不能用这个 Agent（按资源授权）。
     *
     * <p>规则：ADMIN 始终可用；**没有白名单记录 = 对所有人开放**；配了记录就必须在里面。
     * 运行入口（/agui/run）会调它 —— 否则白名单只是界面上的装饰。
     */
    public void assertUsable(Long agentId, Long userId, boolean isAdmin) {
        if (isAdmin || agentId == null) {
            return;
        }
        List<Long> allowed = agentUserMapper.findUserIds(agentId);
        if (!allowed.isEmpty() && !allowed.contains(userId)) {
            throw BizException.forbidden("该 Agent 未授权给你使用，请联系管理员");
        }
    }

    /** 设置某 Agent 的可用用户白名单；传空列表 = 恢复为"对所有人开放"。 */
    @Transactional
    public AgentResponse setAllowedUsers(Long agentId, List<Long> userIds) {
        Agent agent = requireAgent(agentId);
        agentUserMapper.deleteByAgent(agentId);
        if (userIds != null) {
            userIds.stream().distinct().forEach(uid -> {
                com.myagent.agentconfig.entity.AgentUser row =
                        new com.myagent.agentconfig.entity.AgentUser();
                row.setAgentId(agentId);
                row.setUserId(uid);
                agentUserMapper.insert(row);
            });
        }
        cacheInvalidator.invalidate(agentId);
        return toResponse(agentMapper.selectById(agent.getId()));
    }

    @Transactional
    public AgentResponse create(AgentRequest request) {
        validate(request);
        ensureUnique(request.name(), request.agentId(), null);
        requireModel(request.modelId());

        Agent agent = new Agent();
        applyRequest(agent, request);
        agentMapper.insert(agent);

        replaceBindings(agent.getId(), request.skillIds(), request.mcpServerIds());
        return toResponse(agent);
    }

    @Transactional
    public AgentResponse update(Long id, AgentRequest request) {
        Agent agent = requireAgent(id);
        validate(request);
        ensureUnique(request.name(), request.agentId(), id);
        requireModel(request.modelId());

        applyRequest(agent, request);
        agentMapper.updateById(agent);

        replaceBindings(id, request.skillIds(), request.mcpServerIds());
        // 配置变更后让运行中的 Agent 实例失效，新会话即生效。
        cacheInvalidator.invalidate(id);
        return toResponse(agent);
    }

    @Transactional
    public void delete(Long id) {
        requireAgent(id);
        // 关联表对 agent 是 ON DELETE CASCADE，但仍显式清理以便审计清晰。
        agentSkillMapper.deleteByAgentId(id);
        agentMcpServerMapper.deleteByAgentId(id);
        agentMapper.deleteById(id);
        cacheInvalidator.invalidate(id);
    }

    /** 整体替换绑定：先校验全部 id 存在，再写入，保证不产生半更新。 */
    private void replaceBindings(Long agentId, List<Long> skillIds, List<Long> mcpServerIds) {
        List<Long> skills = distinct(skillIds);
        List<Long> servers = distinct(mcpServerIds);

        for (Long skillId : skills) {
            if (agentSkillMapper.countExistingSkill(skillId) == 0) {
                throw BizException.badRequest("Skill 不存在: " + skillId);
            }
        }
        for (Long serverId : servers) {
            if (agentMcpServerMapper.countExistingMcpServer(serverId) == 0) {
                throw BizException.badRequest("MCP Server 不存在: " + serverId);
            }
        }

        agentSkillMapper.deleteByAgentId(agentId);
        agentMcpServerMapper.deleteByAgentId(agentId);
        skills.forEach(skillId -> agentSkillMapper.insertLink(agentId, skillId));
        servers.forEach(serverId -> agentMcpServerMapper.insertLink(agentId, serverId));
    }

    private static List<Long> distinct(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    private Agent requireAgent(Long id) {
        Agent agent = id == null ? null : agentMapper.selectById(id);
        if (agent == null) {
            throw BizException.notFound("Agent 不存在: " + id);
        }
        return agent;
    }

    private void requireModel(Long modelId) {
        LlmModel model = modelId == null ? null : llmModelMapper.selectById(modelId);
        if (model == null) {
            throw BizException.badRequest("引用的模型不存在: " + modelId);
        }
    }

    private void validate(AgentRequest request) {
        String mode = normalize(request.permissionMode());
        if (!PERMISSION_MODES.contains(mode)) {
            throw BizException.badRequest("permissionMode 非法: " + request.permissionMode());
        }
        if (request.status() != null && !STATUSES.contains(request.status().trim().toUpperCase())) {
            throw BizException.badRequest("status 非法: " + request.status());
        }
        if (request.temperature() != null
                && (request.temperature().compareTo(java.math.BigDecimal.ZERO) < 0
                        || request.temperature().compareTo(new java.math.BigDecimal("2")) > 0)) {
            throw BizException.badRequest("temperature 必须在 0~2 之间");
        }
        if (request.maxTokens() != null && request.maxTokens() <= 0) {
            throw BizException.badRequest("maxTokens 必须为正整数");
        }
        if (request.maxIterations() != null && request.maxIterations() <= 0) {
            throw BizException.badRequest("maxIterations 必须为正整数");
        }
    }

    private void ensureUnique(String name, String agentId, Long excludeId) {
        Long byName = agentMapper.selectCount(Wrappers.<Agent>lambdaQuery()
                .eq(Agent::getName, name)
                .ne(excludeId != null, Agent::getId, excludeId));
        if (byName > 0) {
            throw BizException.conflict("Agent 名称已存在: " + name);
        }
        Long byAgentId = agentMapper.selectCount(Wrappers.<Agent>lambdaQuery()
                .eq(Agent::getAgentId, agentId)
                .ne(excludeId != null, Agent::getId, excludeId));
        if (byAgentId > 0) {
            throw BizException.conflict("agent_id 已存在: " + agentId);
        }
    }

    private void applyRequest(Agent agent, AgentRequest request) {
        agent.setName(request.name());
        agent.setAgentId(request.agentId());
        agent.setSysPrompt(request.sysPrompt());
        agent.setModelId(request.modelId());
        agent.setTemperature(request.temperature());
        agent.setMaxTokens(request.maxTokens());
        agent.setPermissionMode(normalize(request.permissionMode()));
        agent.setMaxIterations(
                request.maxIterations() == null ? DEFAULT_MAX_ITERATIONS : request.maxIterations());
        agent.setStatus(StringUtils.hasText(request.status())
                ? request.status().trim().toUpperCase()
                : "ENABLED");
        // tier1-upgrades ④：Agent 级额度（空=不限）
        agent.setDailyQuota(request.dailyQuota());
        agent.setMonthlyQuota(request.monthlyQuota());
        agent.setQuotaCurrency(request.quotaCurrency());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private AgentResponse toResponse(Agent agent) {
        return new AgentResponse(
                agent.getId(),
                agent.getName(),
                agent.getAgentId(),
                agent.getSysPrompt(),
                agent.getModelId(),
                agent.getTemperature(),
                agent.getMaxTokens(),
                agent.getPermissionMode(),
                agent.getMaxIterations(),
                agent.getDailyQuota(),
                agent.getMonthlyQuota(),
                agent.getQuotaCurrency(),
                agent.getStatus(),
                agentSkillMapper.findSkillIds(agent.getId()),
                agentMcpServerMapper.findMcpServerIds(agent.getId()),
                agent.getCreatedAt(),
                agent.getUpdatedAt(),
                // 空列表 = 对所有人开放（界面显示"全部用户"）
                agentUserMapper.findUserIds(agent.getId()));
    }
}
