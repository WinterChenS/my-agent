package com.myagent.connectorconfig.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 连接器响应：**凭据永不回显**（敏感键统一返回 {@code ***}）。
 *
 * @param account 账号摘要（邮箱就是邮箱地址），列表页显示用
 * @param config 掩码后的配置（编辑表单回填）
 * @param capabilities 该类型的能力标签
 * @param agentIds 已绑定的 Agent
 */
public record ConnectorResponse(
        Long id,
        String type,
        String typeName,
        String name,
        String alias,
        String account,
        Boolean enabled,
        String status,
        String lastError,
        OffsetDateTime lastCheckedAt,
        String inboundMode,
        List<String> capabilities,
        Map<String, String> config,
        List<Long> agentIds,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
