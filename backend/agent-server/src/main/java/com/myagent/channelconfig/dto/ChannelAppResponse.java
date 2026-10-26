package com.myagent.channelconfig.dto;

import java.time.OffsetDateTime;
import java.util.Map;

/** 渠道应用回显：凭据只给掩码，不给明文。 */
public record ChannelAppResponse(
        Long id,
        String channelType,
        String name,
        Boolean enabled,
        Long agentId,
        String agentName,
        Long ownerUserId,
        String ownerName,
        String status,
        String lastError,
        /** 非敏感配置（appKey/robotCode/mode…），敏感项替换成 {@code ***}。 */
        Map<String, String> config,
        long peerCount,
        /** 飞书回调 / 钉钉 HTTP 回调要用的完整地址（Stream 模式为 null）。 */
        String webhookPath,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
