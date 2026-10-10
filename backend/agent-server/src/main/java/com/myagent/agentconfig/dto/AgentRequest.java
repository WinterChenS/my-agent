package com.myagent.agentconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * Agent 新建/更新请求。
 *
 * <p>{@code skillIds} / {@code mcpServerIds} 为绑定集合，更新时整体替换。
 */
public record AgentRequest(
        @NotBlank(message = "name 不能为空") String name,
        @NotBlank(message = "agentId 不能为空") String agentId,
        String sysPrompt,
        @NotNull(message = "modelId 不能为空") Long modelId,
        BigDecimal temperature,
        Integer maxTokens,
        @NotBlank(message = "permissionMode 不能为空") String permissionMode,
        Integer maxIterations,
        String status,
        List<Long> skillIds,
        List<Long> mcpServerIds) {}
