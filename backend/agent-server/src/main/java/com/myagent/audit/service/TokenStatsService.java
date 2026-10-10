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

    public TokenStatsService(TokenUsageMapper tokenUsageMapper) {
        this.tokenUsageMapper = tokenUsageMapper;
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("promptTokens", number(totals, "prompt_tokens"));
        result.put("completionTokens", number(totals, "completion_tokens"));
        result.put("totalTokens", number(totals, "total_tokens"));
        result.put("byModel", byModel);
        result.put("byDay", byDay);
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
        return PageResult.of(
                page.getRecords().stream().map(TokenStatsService::toRow).toList(),
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

    private static Map<String, Object> toRow(TokenUsage usage) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", usage.getId());
        row.put("userId", usage.getUserId());
        row.put("sessionId", usage.getSessionId());
        row.put("agentId", usage.getAgentId());
        row.put("modelId", usage.getModelId());
        row.put("providerType", usage.getProviderType());
        row.put("promptTokens", usage.getPromptTokens());
        row.put("completionTokens", usage.getCompletionTokens());
        row.put("totalTokens", usage.getTotalTokens());
        row.put("requestId", usage.getRequestId());
        row.put("createdAt", usage.getCreatedAt());
        return row;
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
