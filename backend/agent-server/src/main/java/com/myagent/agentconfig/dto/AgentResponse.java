package com.myagent.agentconfig.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Agent 响应：包含绑定集合，供 {@code AgentFactory} 作为输入实体树使用。 */
public record AgentResponse(
        Long id,
        String name,
        String agentId,
        String sysPrompt,
        Long modelId,
        BigDecimal temperature,
        Integer maxTokens,
        String permissionMode,
        Integer maxIterations,
        String status,
        List<Long> skillIds,
        List<Long> mcpServerIds,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
