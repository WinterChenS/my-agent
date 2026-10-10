package com.myagent.chat.dto;

import java.time.OffsetDateTime;

/** 会话响应。 */
public record SessionResponse(
        Long id,
        Long agentId,
        String title,
        String threadId,
        OffsetDateTime lastActiveAt,
        OffsetDateTime createdAt) {}
