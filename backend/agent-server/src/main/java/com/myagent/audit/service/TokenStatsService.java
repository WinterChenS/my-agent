package com.myagent.audit.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.myagent.audit.entity.TokenUsage;
import com.myagent.audit.mapper.TokenUsageMapper;
import com.myagent.common.BizException;
import com.myagent.common.PageResult;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Token 审计查询：按用户维度聚合，走 SQL（不在应用层实时计算）。
 *
 * <p>权限：{@code ADMIN} 可查全员；普通用户只能查自己（查询他人时自动纠正为本人，避免越权读取）。
 */
@Service
public class TokenStatsService {

    private final TokenUsageMapper tokenUsageMapper;
    private final com.myagent.modelconfig.mapper.LlmModelMapper llmModelMapper;
    private final com.myagent.agentconfig.mapper.AgentMapper agentMapper;
    private final com.myagent.auth.mapper.SysUserMapper sysUserMapper;

    public TokenStatsService(
            TokenUsageMapper tokenUsageMapper,
            com.myagent.modelconfig.mapper.LlmModelMapper llmModelMapper,
            com.myagent.agentconfig.mapper.AgentMapper agentMapper,
            com.myagent.auth.mapper.SysUserMapper sysUserMapper) {
        this.tokenUsageMapper = tokenUsageMapper;
        this.llmModelMapper = llmModelMapper;
        this.agentMapper = agentMapper;
        this.sysUserMapper = sysUserMapper;
    }

    /** 解析实际查询目标用户：非管理员强制为自己。 */
    private Long resolveTargetUser(Long callerId, String callerRole, Long requestedUserId) {
        if ("ADMIN".equalsIgnoreCase(callerRole)) {
            return requestedUserId == null ? callerId : requestedUserId;
        }
        if (requestedUserId != null && !requestedUserId.equals(callerId)) {
            throw BizException.forbidden("普通用户只能查询自己的 Token 用量");
        }
        return callerId;
    }

    public Map<String, Object> summary(
            Long callerId, String callerRole, Long requestedUserId, OffsetDateTime from, OffsetDateTime to) {
        Long userId = resolveTargetUser(callerId, callerRole, requestedUserId);
        OffsetDateTime start = from == null ? OffsetDateTime.now().minusDays(30) : from;
        OffsetDateTime end = to == null ? OffsetDateTime.now().plusDays(1) : to;

        Map<String, Object> totals = tokenUsageMapper.totals(userId, start, end);
        List<Map<String, Object>> byModel = tokenUsageMapper.byModel(userId, start, end);
        List<Map<String, Object>> byDay = tokenUsageMapper.byDay(userId, start, end);
        List<Map<String, Object>> byAgent = tokenUsageMapper.byAgent(userId, start, end);
        List<Map<String, Object>> byProvider = tokenUsageMapper.byProvider(userId, start, end);
        Map<String, Object> stats = tokenUsageMapper.usageStats(userId, start, end);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("promptTokens", number(totals, "prompt_tokens"));
        result.put("completionTokens", number(totals, "completion_tokens"));
        result.put("totalTokens", number(totals, "total_tokens"));
        // 缓存命中 token（厂商返回才有值）与"按缓存价计"的那部分
        result.put("cacheTokens", number(totals, "cache_tokens"));
        // Phase 15：价格生效——按货币汇总成本（不同货币不混加）
        java.math.BigDecimal costTotal = java.math.BigDecimal.ZERO;
        String currency = null;
        boolean mixed = false;
        for (Map<String, Object> row : byModel) {
            Object cost = row.get("cost_total");
            if (!(cost instanceof Number number)) {
                continue;
            }
            java.math.BigDecimal value = new java.math.BigDecimal(number.toString());
            Object cur = row.get("currency");
            String rowCurrency = cur == null ? "CNY" : cur.toString();
            if (currency == null) {
                currency = rowCurrency;
            } else if (!currency.equals(rowCurrency)) {
                mixed = true;
            }
            costTotal = costTotal.add(value);
        }
        result.put("costTotal", costTotal);
        result.put("costCurrency", mixed ? "MIXED" : currency);
        // 分类成本：未命中输入 / 缓存命中 / 输出（byModel 已按模型算好，这里汇总）
        java.math.BigDecimal costInput = java.math.BigDecimal.ZERO;
        java.math.BigDecimal costCache = java.math.BigDecimal.ZERO;
        java.math.BigDecimal costOutput = java.math.BigDecimal.ZERO;
        for (Map<String, Object> row : byModel) {
            costInput = costInput.add(decimal(row, "cost_input"));
            costCache = costCache.add(decimal(row, "cost_cache"));
            costOutput = costOutput.add(decimal(row, "cost_output"));
        }
        result.put("costInput", costInput);
        result.put("costCache", costCache);
        result.put("costOutput", costOutput);
        result.put("byModel", byModel);
        result.put("byDay", byDay);
        // 更多维度：按 Agent、按供应商；以及调用/会话/Agent/模型 的计数（KPI 卡片用）
        result.put("byAgent", byAgent);
        result.put("byProvider", byProvider);
        result.put("records", number(stats, "records"));
        result.put("sessions", number(stats, "sessions"));
        result.put("agentCount", number(stats, "agents"));
        result.put("modelCount", number(stats, "models"));
        result.put("rangeFrom", start.toString());
        result.put("rangeTo", end.toString());
        return result;
    }

    public PageResult<Map<String, Object>> records(
            Long callerId,
            String callerRole,
            Long requestedUserId,
            OffsetDateTime from,
            OffsetDateTime to,
            long current,
            long size) {
        Long userId = resolveTargetUser(callerId, callerRole, requestedUserId);
        var query = Wrappers.<TokenUsage>lambdaQuery()
                .eq(TokenUsage::getUserId, userId)
                .ge(from != null, TokenUsage::getCreatedAt, from)
                .le(to != null, TokenUsage::getCreatedAt, to)
                .orderByDesc(TokenUsage::getId);
        IPage<TokenUsage> page = tokenUsageMapper.selectPage(new Page<>(current, size), query);
        List<TokenUsage> rows = page.getRecords();
        // 名称映射：一次性按 id 批量取，避免每行查一次库
        Map<Long, String> modelNames = names(
                rows.stream().map(TokenUsage::getModelId).distinct().toList(),
                ids -> llmModelMapper
                        .selectList(Wrappers.<com.myagent.modelconfig.entity.LlmModel>lambdaQuery()
                                .in(com.myagent.modelconfig.entity.LlmModel::getId, ids))
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.myagent.modelconfig.entity.LlmModel::getId,
                                m -> m.getDisplayName() == null ? m.getModelId() : m.getDisplayName(),
                                (a, b) -> a)));
        Map<Long, String> agentNames = names(
                rows.stream().map(TokenUsage::getAgentId).distinct().toList(),
                ids -> agentMapper
                        .selectList(Wrappers.<com.myagent.agentconfig.entity.Agent>lambdaQuery()
                                .in(com.myagent.agentconfig.entity.Agent::getId, ids))
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.myagent.agentconfig.entity.Agent::getId,
                                com.myagent.agentconfig.entity.Agent::getName,
                                (a, b) -> a)));
        Map<Long, String> userNames = names(
                rows.stream().map(TokenUsage::getUserId).distinct().toList(),
                ids -> sysUserMapper
                        .selectList(Wrappers.<com.myagent.auth.entity.SysUser>lambdaQuery()
                                .in(com.myagent.auth.entity.SysUser::getId, ids))
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.myagent.auth.entity.SysUser::getId,
                                u -> u.getDisplayName() == null ? u.getUsername() : u.getDisplayName(),
                                (a, b) -> a)));
        return PageResult.of(
                rows.stream()
                        .map(row -> toRow(
                                row,
                                modelNames.get(row.getModelId()),
                                agentNames.get(row.getAgentId()),
                                userNames.get(row.getUserId())))
                        .toList(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize());
    }

    /** 有消耗的用户列表。 */
    public List<Map<String, Object>> users(Long callerId, String callerRole) {
        List<Map<String, Object>> all = tokenUsageMapper.usersWithUsage();
        if ("ADMIN".equalsIgnoreCase(callerRole)) {
            return all;
        }
        return all.stream()
                .filter(row -> callerId.equals(asLong(row.get("user_id"))))
                .toList();
    }

    /** 只查非空 id，空集合直接返回空表（避免 IN () 语法错误）。 */
    private static Map<Long, String> names(
            List<Long> ids, java.util.function.Function<List<Long>, Map<Long, String>> loader) {
        List<Long> nonNull = ids.stream().filter(java.util.Objects::nonNull).toList();
        if (nonNull.isEmpty()) {
            return Map.of();
        }
        return loader.apply(nonNull);
    }

    private static Map<String, Object> toRow(
            TokenUsage usage, String modelName, String agentName, String userName) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", usage.getId());
        row.put("userId", usage.getUserId());
        row.put("userName", userName);
        row.put("sessionId", usage.getSessionId());
        row.put("agentId", usage.getAgentId());
        row.put("agentName", agentName);
        row.put("modelId", usage.getModelId());
        row.put("modelName", modelName);
        row.put("providerType", usage.getProviderType());
        row.put("promptTokens", usage.getPromptTokens());
        row.put("completionTokens", usage.getCompletionTokens());
        row.put("totalTokens", usage.getTotalTokens());
        row.put("cacheTokens", usage.getCacheTokens() == null ? 0 : usage.getCacheTokens());
        row.put("requestId", usage.getRequestId());
        row.put("createdAt", usage.getCreatedAt());
        return row;
    }

    /** 从聚合行里取小数（null 视为 0）。 */
    private static java.math.BigDecimal decimal(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value == null) {
            return java.math.BigDecimal.ZERO;
        }
        if (value instanceof java.math.BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return java.math.BigDecimal.valueOf(n.doubleValue());
        }
        return new java.math.BigDecimal(String.valueOf(value));
    }

    private static long number(Map<String, Object> map, String key) {
        if (map == null) {
            return 0L;
        }
        Object value = map.get(key) == null ? map.get(key.toUpperCase()) : map.get(key);
        return asLong(value);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
