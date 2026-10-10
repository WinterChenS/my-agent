package com.myagent.approval.dto;

import java.time.OffsetDateTime;

/** 审批记录响应。 */
public record ApprovalResponse(
        Long id,
        Long sessionId,
        String interruptId,
        String toolName,
        Object args,
        String status,
        Long decidedBy,
        OffsetDateTime decidedAt,
        OffsetDateTime createdAt) {}
